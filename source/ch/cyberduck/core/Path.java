package ch.cyberduck.core;

/*
 *  Copyright (c) 2005 David Kocher. All rights reserved.
 *  http://cyberduck.ch/
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  Bug fixes, suggestions and comments should be sent to:
 *  dkocher@cyberduck.ch
 */

import com.apple.cocoa.foundation.NSBundle;
import com.apple.cocoa.foundation.NSDictionary;
import com.apple.cocoa.foundation.NSMutableDictionary;
import com.apple.cocoa.foundation.NSObject;

import org.apache.log4j.Logger;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.TimeZone;
import java.util.Calendar;

/**
 * @version $Id$
 */
public abstract class Path extends NSObject {
    private static Logger log = Logger.getLogger(Path.class);

    private String path = null;
    private Local local = null;

    public Status status = new Status();
    public Attributes attributes = new Attributes();

    public static final int FILE_TYPE = 1;
    public static final int DIRECTORY_TYPE = 2;
    public static final int SYMBOLIC_LINK_TYPE = 4;

    public static final String DELIMITER = "/";

    public Path(NSDictionary dict) {
        Object pathObj = dict.objectForKey("Remote");
        if (pathObj != null) {
            this.setPath((String) pathObj);
        }
        Object localObj = dict.objectForKey("Local");
        if (localObj != null) {
            this.setLocal(new Local((String) localObj));
        }
        Object attributesObj = dict.objectForKey("Attributes");
        if (attributesObj != null) {
            this.attributes = new Attributes((NSDictionary) attributesObj);
        }
    }

    public NSDictionary getAsDictionary() {
        NSMutableDictionary dict = new NSMutableDictionary();
        dict.setObjectForKey(this.getAbsolute(), "Remote");
        dict.setObjectForKey(this.getLocal().toString(), "Local");
        dict.setObjectForKey(this.attributes.getAsDictionary(), "Attributes");
        return dict;
    }

    public Object clone(Session session) {
        Path copy = PathFactory.createPath(session, this.getAsDictionary());
        copy.attributes = (Attributes) this.attributes.clone();
        return copy;
    }

    protected Path() {
        super();
    }

    /**
     * A remote path where nothing is known about a local equivalent.
     *
     * @param parent the absolute directory
     * @param name   the file relative to param path
     */
    protected Path(String parent, String name) {
        this.setPath(parent, name);
    }

    /**
     * A remote path where nothing is known about a local equivalent.
     *
     * @param path The absolute path of the remote file
     */
    protected Path(String path) {
        this.setPath(path);
    }

    /**
     * Create a new path where you know the local file already exists
     * and the remote equivalent might be created later.
     * The remote filename will be extracted from the local file.
     *
     * @param parent The absolute path to the parent directory on the remote host
     * @param local  The associated local file
     */
    protected Path(String parent, Local local) {
        this.setPath(parent, local);
    }

    public void setPath(String parent, Local file) {
        this.setPath(parent, file.getName());
        this.setLocal(file);
        if (this.getLocal().exists()) {
            this.attributes.setType(this.getLocal().isDirectory() ? Path.DIRECTORY_TYPE : Path.FILE_TYPE);
        }
    }

    /**
     * @param parent The parent directory
     * @param name   The relative filename
     */
    public void setPath(String parent, String name) {
        if (parent.charAt(parent.length() - 1) == '/') {
            this.setPath(parent + name);
        }
        else {
            this.setPath(parent + DELIMITER + name);
        }
    }

    public void setPath(String p) {
        if ((p.charAt(p.length() - 1) == '/') && (p.length() > 1)) {
            this.path = p.substring(0, p.length() - 1);
        }
        else {
            this.path = p;
        }
        this.parent = null;
    }

    public abstract void reset();

    private Path parent;

    /*
      * @return My parent directory
      */
    public Path getParent() {
        if (null == parent) {
            int index = this.getAbsolute().length() - 1;
            if (this.getAbsolute().charAt(index) == '/') {
                if (index > 0)
                    index--;
            }
            int cut = this.getAbsolute().lastIndexOf('/', index);
            if (cut > 0) {
                this.parent = PathFactory.createPath(this.getSession(), this.getAbsolute().substring(0, cut));
                this.parent.attributes.setType(Path.DIRECTORY_TYPE);
            }
            else {//if (index == 0) //parent is root
                this.parent = PathFactory.createPath(this.getSession(), DELIMITER);
                this.parent.attributes.setType(Path.DIRECTORY_TYPE);
            }
        }
        return this.parent;
    }

    /**
     * @throws NullPointerException if session is not initialized
     */
    public Host getHost() {
        return this.getSession().getHost();
    }

    /**
     * @throws NullPointerException if session is not initialized
     */
    public void invalidate() {
        this.getSession().cache().invalidate(this);
    }

    /**
     * Request a unsorted and unfiltered file listing from the server.
     *
     * @return null if there is an error accessing this directory
     */
    public AttributedList list() {
        return this.list(new NullComparator(), new NullFilter());
    }

    /**
     * Request a sorted and filtered file listing from the server. Has to be a directory.
     *
     * @param comparator The comparator to sort the listing with
     * @param filter     The filter to exlude certain files
     * @return null if there is an error accessing this directory
     */
    public abstract AttributedList list(Comparator comparator, Filter filter);

    /**
     * Remove this file from the remote host. Does not affect any corresponding local file
     */
    public abstract void delete();

    /**
     * Changes the session's working directory to this path
     */
    public abstract void cwdir() throws IOException;

    public void mkdir() {
        this.mkdir(false);
    }

    /**
     * @param recursive Create intermediate directories as required.  If this option is
     * not specified, the full path prefix of each operand must already exist
     */
    public abstract void mkdir(boolean recursive);

    /**
     * @param name Must be an absolute path
     */
    public abstract void rename(String name);

    public abstract void changeOwner(String owner, boolean recursive);

    public abstract void changeGroup(String group, boolean recursive);

    /**
     * @param recursive Include subdirectories and files
     */
    public abstract void changePermissions(Permission perm, boolean recursive);

    /**
     * @return true if this paths points to '/'
     */
    public boolean isRoot() {
        return this.getAbsolute().equals(DELIMITER) || this.getAbsolute().indexOf('/') == -1;
    }

    public boolean isChild(Path p) {
        for (Path parent = this.getParent(); !parent.isRoot(); parent = parent.getParent()) {
            if (parent.equals(p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return the path relative to its parent directory
     */
    public String getName() {
        if (this.isRoot()) {
            return DELIMITER;
        }
        String abs = this.getAbsolute();
        int index = abs.lastIndexOf('/');
        return (index > 0) ? abs.substring(index + 1) : abs.substring(1);
    }

    /**
     * @return the absolute path name, e.g. /home/user/filename
     */
    public String getAbsolute() {
        return this.path;
    }

    public void setLocal(Local file) {
        this.local = file;
    }

    /**
     * @return The local alias of this path
     */
    public Local getLocal() {
        //default value if not set explicitly, i.e. with drag and drop
        if (null == this.local) {
            return new Local(Preferences.instance().getProperty("queue.download.folder"), this.getName());
        }
        return this.local;
    }

    public Path getRemote() {
        return this;
    }

    /**
     * @return the extension if any
     */
    public String getExtension() {
        String name = this.getName();
        int index = name.lastIndexOf(".");
        if (index != -1) {
            return name.substring(index + 1, name.length());
        }
        return null;
    }

    /**
     *
     * @return
     */
    public abstract Session getSession();

    /**
     *
     */
    public abstract void download();

    /**
     *
     */
    public abstract void upload();

    private boolean skip = false;

    /**
     *
     * @param ignoreTransferRequests
     */
    public void setSkipped(boolean ignoreTransferRequests) {
        log.debug("setSkipped:" + ignoreTransferRequests);
        this.skip = ignoreTransferRequests;
    }

    /**
     *
     * @return
     */
    public boolean isSkipped() {
        return this.skip;
    }

    /**
     * @param in  The stream to read from
     * @param out The stream to write to
     */
    public void upload(java.io.OutputStream out, java.io.InputStream in) throws IOException {
        if (log.isDebugEnabled()) {
            log.debug("upload(" + out.toString() + ", " + in.toString());
        }
        this.getSession().message(NSBundle.localizedString("Uploading", "Status", "") + " " + this.getName());
        if (this.status.isResume()) {
            long skipped = in.skip(this.status.getCurrent());
            log.info("Skipping " + skipped + " bytes");
            if (skipped < this.status.getCurrent()) {
                throw new IOException("Resume failed: Skipped " + skipped + " bytes instead of " + this.status.getCurrent());
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("upload(" + in.toString() + ", " + out.toString());
        }
        int chunksize = Preferences.instance().getInteger("connection.buffer");
        byte[] chunk = new byte[chunksize];
        int amount = 0;
        long current = this.status.getCurrent();
        boolean complete = false;
        // read from socket (bytes) & write to file in chunks
        while (!complete && !status.isCanceled()) {
            amount = in.read(chunk, 0, chunksize);
            if (-1 == amount) {
                complete = true;
            }
            else {
                out.write(chunk, 0, amount);
                this.status.setCurrent(current += amount);
                out.flush();
            }
        }
        this.status.setComplete(complete);
    }

    /**
     * @param in  The stream to read from
     * @param out The stream to write to
     */
    public void download(java.io.InputStream in, java.io.OutputStream out) throws IOException {
        if (log.isDebugEnabled()) {
            log.debug("download(" + in.toString() + ", " + out.toString());
        }
        this.getSession().message(NSBundle.localizedString("Downloading", "Status", "") + " " + this.getName());
        int chunksize = Preferences.instance().getInteger("connection.buffer");
        byte[] chunk = new byte[chunksize];
        int amount = 0;
        long current = this.status.getCurrent();
        boolean complete = false;
        boolean updateProgress = this.attributes.getSize() > Status.MEGA * 5;
        int step = 0;
        this.getLocal().setProgress(step);
        // read from socket (bytes) & write to file in chunks
        while (!complete && !status.isCanceled()) {
            amount = in.read(chunk, 0, chunksize);
            if (-1 == amount) {
                complete = true;
            }
            else {
                out.write(chunk, 0, amount);
                this.status.setCurrent(current += amount);
                if (updateProgress) {
                    int fraction = (int) (status.getCurrent() / this.attributes.getSize() * 10);
                    if ((fraction > step)) {
                        this.getLocal().setProgress(++step);
                    }
                }
                out.flush();
            }
        }
        if (complete) {
            this.getLocal().setProgress(-1);
        }
        this.status.setComplete(complete);
    }

    /**
     *
     * @return true if the path exists (or is cached!)
     */
    public boolean exists() {
        if (this.isRoot()) {
            return true;
        }
        List listing = this.getParent().list();
        if (null == listing) {
            return false;
        }
        return listing.contains(this);
    }

    public int hashCode() {
        return this.getAbsolute().hashCode();
    }

    public boolean equals(Object other) {
        if (null == other) {
            return false;
        }
        if (other instanceof Path) {
            //case sensitive comparison; incompatibility with non-case sensitive systems!
            return this.getAbsolute().equals(((Path) other).getAbsolute());
        }
        return false;
    }

    public String toString() {
        return this.getAbsolute();
    }

    protected void finalize() throws java.lang.Throwable {
        log.debug("finalize:" + super.toString());
        super.finalize();
    }
}
