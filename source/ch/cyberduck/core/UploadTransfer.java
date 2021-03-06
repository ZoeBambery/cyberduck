package ch.cyberduck.core;

/*
 * Copyright (c) 2002-2010 David Kocher. All rights reserved.
 *
 * http://cyberduck.ch/
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * Bug fixes, suggestions and comments should be sent to:
 * dkocher@cyberduck.ch
 */

import ch.cyberduck.core.i18n.Locale;
import ch.cyberduck.core.io.BandwidthThrottle;
import ch.cyberduck.core.serializer.Serializer;
import ch.cyberduck.ui.DateFormatterFactory;
import ch.cyberduck.ui.growl.Growl;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;

import java.text.MessageFormat;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * @version $Id$
 */
public class UploadTransfer extends Transfer {
    private static Logger log = Logger.getLogger(UploadTransfer.class);

    public UploadTransfer(Path root) {
        super(root);
    }

    public UploadTransfer(List<Path> roots) {
        super(roots);
    }

    public <T> UploadTransfer(T dict, Session s) {
        super(dict, s);
    }

    @Override
    public <T> T getAsDictionary() {
        final Serializer dict = super.getSerializer();
        dict.setStringForKey(String.valueOf(KIND_UPLOAD), "Kind");
        return dict.<T>getSerialized();
    }

    @Override
    protected void init() {
        log.debug("init");
        this.bandwidth = new BandwidthThrottle(
                Preferences.instance().getFloat("queue.upload.bandwidth.bytes"));
    }

    @Override
    protected void normalize() {
        log.debug("normalize");
        final List<Path> normalized = new Collection<Path>();
        for(Path upload : this.getRoots()) {
            if(!this.check()) {
                return;
            }
            this.getSession().message(MessageFormat.format(Locale.localizedString("Prepare {0}", "Status"), upload.getName()));
            boolean duplicate = false;
            for(Iterator<Path> iter = normalized.iterator(); iter.hasNext();) {
                Path n = iter.next();
                if(upload.getLocal().isChild(n.getLocal())) {
                    // The selected file is a child of a directory already included
                    duplicate = true;
                    break;
                }
                if(n.getLocal().isChild(upload.getLocal())) {
                    iter.remove();
                }
                if(upload.equals(n)) {
                    // The selected file has the same name; if uploaded as a root element
                    // it would overwrite the earlier
                    final String parent = upload.getParent().getAbsolute();
                    final String filename = upload.getName();
                    String proposal;
                    int no = 0;
                    int index = filename.lastIndexOf(".");
                    do {
                        no++;
                        if(index != -1 && index != 0) {
                            proposal = filename.substring(0, index)
                                    + "-" + no + filename.substring(index);
                        }
                        else {
                            proposal = filename + "-" + no;
                        }
                        upload.setPath(parent, proposal);
                    }
                    while(false);//(upload.exists());
                    if(log.isInfoEnabled()) {
                        log.info("Changed name to:" + upload.getName());
                    }
                }
            }
            // Prunes the list of selected files. Files which are a child of an already included directory
            // are removed from the returned list.
            if(!duplicate) {
                normalized.add(upload);
            }
        }
        this.setRoots(normalized);
    }

    /**
     *
     */
    private abstract class UploadTransferFilter extends TransferFilter {
        public boolean accept(final Path file) {
            if(!file.getLocal().exists()) {
                return false;
            }
            if(file.getLocal().attributes().isSymbolicLink()) {
                if(!UploadTransfer.this.isSymlinkSupported(file)) {
                    final AbstractPath target = file.getLocal().getSymlinkTarget();
                    // Do not transfer files referenced from symlinks pointing to files also included
                    for(Path root : roots) {
                        if(target.isChild(root.getLocal())) {
                            return false;
                        }
                    }
                }
            }
            return true;
        }

        @Override
        public void prepare(Path file) {
            if(file.attributes().isFile()) {
                if(file.getLocal().attributes().isSymbolicLink()) {
                    if(!UploadTransfer.this.isSymlinkSupported(file)) {
                        // A server will resolve the symbolic link when the file is requested.
                        final AbstractPath target = file.getLocal().getSymlinkTarget();
                        size += target.attributes().getSize();
                    }
                    else {
                        // No file size increase for symbolic link to be created on the server
                    }
                }
                else {
                    // Read file size
                    size += file.getLocal().attributes().getSize();
                    if(file.status().isResume()) {
                        transferred += file.attributes().getSize();
                    }
                }
            }
            if(file.attributes().isDirectory()) {
                if(!file.exists()) {
                    file.cache().put(file.<Object>getReference(), new AttributedList<Path>());
                }
            }
        }

        /**
         * Post process
         */
        @Override
        public void complete(Path p) {
            ;
        }
    }

    private final PathFilter<Local> exclusionRegexFilter = new PathFilter<Local>() {
        final Pattern pattern
                = Pattern.compile(Preferences.instance().getProperty("queue.upload.skip.regex"));

        public boolean accept(Local child) {
            if(child.attributes().isDuplicate()) {
                return false;
            }
            try {
                if(Preferences.instance().getBoolean("queue.upload.skip.enable")) {
                    if(pattern.matcher(child.getName()).matches()) {
                        return false;
                    }
                }
            }
            catch(PatternSyntaxException e) {
                log.warn(e.getMessage());
            }
            return true;
        }
    };

    /**
     * File listing cache for children of the root paths not part of the session cache because
     * they only exist on the local file system.
     */
    private final Cache<Path> cache = new Cache<Path>() {
        @Override
        public void clear() {
            super.clear();
            getSession().cache().clear();
        }
    };

    @Override
    public Cache<Path> cache() {
        return cache;
    }

    @Override
    public AttributedList<Path> children(final Path parent) {
        if(log.isDebugEnabled()) {
            log.debug("children:" + parent);
        }
        if(parent.getLocal().attributes().isSymbolicLink()
                && this.isSymlinkSupported(parent)) {
            if(log.isDebugEnabled()) {
                log.debug("Do not list children for symbolic link:" + parent);
            }
            this.cache().put(parent.<Object>getReference(), AttributedList.<Path>emptyList());
        }
        else if(!this.cache().containsKey(parent.<Object>getReference())) {
            if(!parent.getLocal().exists()) {
                // Cannot fetch file listing of non existant file
                this.cache().put(parent.<Object>getReference(), AttributedList.<Path>emptyList());
            }
            else {
                final AttributedList<Path> children = new AttributedList<Path>();
                for(AbstractPath child : parent.getLocal().children(exclusionRegexFilter)) {
                    final Local local = LocalFactory.createLocal(child.getAbsolute());
                    Path upload = PathFactory.createPath(getSession(), parent.getAbsolute(), local);
                    if(upload.exists()) {
                        upload = this.getSession().cache().lookup(upload.getReference());
                        upload.setLocal(local);
                    }
                    children.add(upload);
                }
                this.cache().put(parent.<Object>getReference(), children);
            }
        }
        return this.cache().get(parent.<Object>getReference());
    }

    @Override
    public boolean isResumable() {
        return this.getSession().isUploadResumable();
    }

    private final TransferFilter OVERWRITE_FILTER = new UploadTransferFilter() {
        @Override
        public boolean accept(final Path file) {
            if(file.attributes().isDirectory()) {
                // Do not attempt to create a directory that already exists
                if(file.exists()) {
                    return false;
                }
            }
            return super.accept(file);
        }

        @Override
        public void prepare(final Path file) {
            if(file.attributes().isFile()) {
                file.status().setResume(false);
            }
            super.prepare(file);
        }

    };

    /**
     * Append to existing file.
     */
    private final TransferFilter RESUME_FILTER = new UploadTransferFilter() {
        @Override
        public boolean accept(final Path file) {
            if(file.attributes().isDirectory()) {
                if(file.exists()) {
                    return false;
                }
            }
            if(file.status().isComplete()
                    || file.getLocal().attributes().getSize() == file.attributes().getSize()) {
                // No need to resume completed transfers
                file.status().setComplete(true);
                return false;
            }
            return super.accept(file);
        }

        @Override
        public void prepare(final Path file) {
            if(file.exists()) {
                if(file.attributes().getSize() == -1) {
                    file.readSize();
                }
            }
            if(file.attributes().isFile()) {
                boolean resume = file.isUploadResumable();
                file.status().setResume(resume);
                if(resume) {
                    if(file.attributes().getSize() == -1) {
                        log.warn("Unknown remote size for:" + file.getAbsolute());
                    }
                    else {
                        file.status().setCurrent(file.attributes().getSize());
                    }
                }
            }
            super.prepare(file);
        }
    };

    private final TransferFilter RENAME_FILTER = new UploadTransferFilter() {
        @Override
        public boolean accept(final Path p) {
            // Rename every file
            return super.accept(p);
        }

        @Override
        public void prepare(final Path file) {
            if(file.exists()) {
                final String parent = file.getParent().getAbsolute();
                final String filename = file.getName();
                int no = 0;
                while(file.exists()) {
                    no++;
                    String proposal = FilenameUtils.getBaseName(filename) + "-" + no;
                    if(StringUtils.isNotBlank(FilenameUtils.getExtension(filename))) {
                        proposal += "." + FilenameUtils.getExtension(filename);
                    }
                    file.setPath(parent, proposal);
                }
                if(log.isInfoEnabled()) {
                    log.info("Changed local name to:" + file.getName());
                }
            }
            if(file.attributes().isFile()) {
                file.status().setResume(false);
            }
            super.prepare(file);
        }
    };

    /**
     * Rename existing file on server if there is a conflict.
     */
    private final TransferFilter RENAME_EXISTING_FILTER = new UploadTransferFilter() {
        @Override
        public boolean accept(final Path p) {
            if(p.getSession().isRenameSupported(p)) {
                return super.accept(p);
            }
            return false;
        }

        @Override
        public void prepare(final Path file) {
            Path renamed = file;
            while(renamed.exists()) {
                String proposal = MessageFormat.format(Preferences.instance().getProperty("queue.upload.file.rename.format"),
                        FilenameUtils.getBaseName(file.getName()),
                        DateFormatterFactory.instance().getLongFormat(System.currentTimeMillis(), false).replace(Path.DELIMITER, ':'),
                        StringUtils.isNotEmpty(file.getExtension()) ? "." + file.getExtension() : "");
                renamed = PathFactory.createPath(file.getSession(), renamed.getParent().getAbsolute(),
                        proposal, file.attributes().getType());
            }
            if(!renamed.equals(file)) {
                file.rename(renamed);
            }
            if(file.attributes().isFile()) {
                file.status().setResume(false);
            }
            super.prepare(file);
        }
    };

    /**
     * Skip files that already exist on the server.
     */
    private final TransferFilter SKIP_FILTER = new UploadTransferFilter() {
        @Override
        public boolean accept(final Path file) {
            if(file.exists()) {
                // Set completion status for skipped files
                file.status().setComplete(true);
                return false;
            }
            return super.accept(file);
        }
    };

    @Override
    public TransferFilter filter(final TransferAction action) {
        log.debug("filter:" + action);
        if(action.equals(TransferAction.ACTION_OVERWRITE)) {
            return OVERWRITE_FILTER;
        }
        if(action.equals(TransferAction.ACTION_RESUME)) {
            return RESUME_FILTER;
        }
        if(action.equals(TransferAction.ACTION_RENAME)) {
            return RENAME_FILTER;
        }
        if(action.equals(TransferAction.ACTION_RENAME_EXISTING)) {
            return RENAME_EXISTING_FILTER;
        }
        if(action.equals(TransferAction.ACTION_SKIP)) {
            return SKIP_FILTER;
        }
        if(action.equals(TransferAction.ACTION_CALLBACK)) {
            for(Path upload : this.getRoots()) {
                if(!this.check()) {
                    return null;
                }
                if(upload.exists()) {
                    if(upload.getLocal().attributes().isDirectory()) {
                        if(0 == this.children(upload).size()) {
                            // Do not prompt for existing empty directories
                            continue;
                        }
                    }
                    // Prompt user to choose a filter
                    TransferAction result = prompt.prompt();
                    return this.filter(result); //break out of loop
                }
            }
            // No files exist yet therefore it is most straightforward to use the overwrite action
            return this.filter(TransferAction.ACTION_OVERWRITE);
        }
        return super.filter(action);
    }

    @Override
    public TransferAction action(final boolean resumeRequested, final boolean reloadRequested) {
        log.debug("action:" + resumeRequested + "," + reloadRequested);
        if(resumeRequested) {
            // Force resume
            return TransferAction.ACTION_RESUME;
        }
        if(reloadRequested) {
            return TransferAction.forName(
                    Preferences.instance().getProperty("queue.upload.reload.fileExists")
            );
        }
        // Use default
        return TransferAction.forName(
                Preferences.instance().getProperty("queue.upload.fileExists")
        );
    }

    private boolean isSymlinkSupported(Path file) {
        if(Preferences.instance().getBoolean("local.symboliclink.resolve")) {
            // Resolve links instead
            return false;
        }
        // Create symbolic link only if supported by the host
        if(this.getSession().isCreateSymlinkSupported()) {
            final AbstractPath target = file.getLocal().getSymlinkTarget();
            // Only create symbolic link if target is included in the upload
            for(Path root : roots) {
                if(target.isChild(root.getLocal())) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    protected void transfer(Path file) {
        log.debug("transfer:" + file);
        Permission perm = Permission.EMPTY;
        if(file.getLocal().attributes().isSymbolicLink() && this.isSymlinkSupported(file)) {
            // Make relative symbolic link
            final String target = StringUtils.substringAfter(file.getLocal().getSymlinkTarget().getAbsolute(),
                    file.getLocal().getParent().getAbsolute() + Path.DELIMITER);
            if(log.isDebugEnabled()) {
                log.debug("Symlink " + file + ":" + target);
            }
            file.symlink(target);
            file.status().setComplete(true);
        }
        else if(file.attributes().isFile()) {
            if(this.getSession().isUnixPermissionsSupported()) {
                if(Preferences.instance().getBoolean("queue.upload.changePermissions")) {
                    if(file.exists()) {
                        // Do not overwrite permissions for existing file.
                        if(file.attributes().getPermission().equals(Permission.EMPTY)) {
                            file.readUnixPermission();
                        }
                        perm = file.attributes().getPermission();
                    }
                }
            }
            String original = file.getName();
            if(Preferences.instance().getBoolean("queue.upload.file.temporary")
                    && file.getSession().isRenameSupported(file)) {
                String temporary = MessageFormat.format(Preferences.instance().getProperty("queue.upload.file.temporary.format"),
                        file.getName(), UUID.randomUUID().toString());
                file.setPath(file.getParent(), temporary);
            }
            // Transfer
            file.upload(bandwidth, new AbstractStreamListener() {
                @Override
                public void bytesSent(long bytes) {
                    transferred += bytes;
                }
            });
            if(file.status().isComplete()) {
                if(Preferences.instance().getBoolean("queue.upload.file.temporary")
                        && file.getSession().isRenameSupported(file)) {
                    file.rename(PathFactory.createPath(file.getSession(), file.getParent().getAbsolute(),
                            original, file.attributes().getType()));
                    file.setPath(file.getParent(), original);
                }
            }
        }
        else if(file.attributes().isDirectory()) {
            if(file.getSession().isCreateFolderSupported(file)) {
                file.mkdir();
            }
        }
        if(!file.status().isCanceled() && file.status().isComplete()) {
            if(this.getSession().isAclSupported()) {
                ; // Currently handled in S3 only.
            }
            if(this.getSession().isUnixPermissionsSupported()) {
                if(Preferences.instance().getBoolean("queue.upload.changePermissions")) {
                    if(perm.equals(Permission.EMPTY)) {
                        if(Preferences.instance().getBoolean("queue.upload.permissions.useDefault")) {
                            if(file.attributes().isFile()) {
                                perm = new Permission(
                                        Preferences.instance().getInteger("queue.upload.permissions.file.default"));
                            }
                            else if(file.attributes().isDirectory()) {
                                perm = new Permission(
                                        Preferences.instance().getInteger("queue.upload.permissions.folder.default"));
                            }
                        }
                        else {
                            if(file.getLocal().exists()) {
                                // Read permissions from local file
                                perm = file.getLocal().attributes().getPermission();
                            }
                        }
                    }
                    if(perm.equals(Permission.EMPTY)) {
                        log.debug("Skip writing empty permissions for:" + this.toString());
                    }
                    else {
                        file.writeUnixPermission(perm, false);
                    }
                }
            }
            if(file.attributes().isFile() && file.getSession().isTimestampSupported()) {
                if(Preferences.instance().getBoolean("queue.upload.preserveDate")) {
                    // Read timestamps from local file
                    file.writeTimestamp(file.getLocal().attributes().getCreationDate(),
                            file.getLocal().attributes().getModificationDate(),
                            file.getLocal().attributes().getAccessedDate());
                }
            }
        }
    }

    @Override
    protected void fireDidTransferPath(Path file) {
        if(file.attributes().isDirectory() && file.getSession().isTimestampSupported()) {
            if(Preferences.instance().getBoolean("queue.upload.preserveDate")) {
                // Read timestamps from local file
                file.writeTimestamp(file.getLocal().attributes().getCreationDate(),
                        file.getLocal().attributes().getModificationDate(),
                        file.getLocal().attributes().getAccessedDate());
            }
        }
        super.fireDidTransferPath(file);
    }

    @Override
    protected void fireTransferDidEnd() {
        if(this.isReset() && this.isComplete() && !this.isCanceled() && !(this.getTransferred() == 0)) {
            Growl.instance().notify("Upload complete", getName());
        }
        super.fireTransferDidEnd();
    }
}