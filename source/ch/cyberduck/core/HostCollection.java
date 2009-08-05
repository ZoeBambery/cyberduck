package ch.cyberduck.core;

/*
 *  Copyright (c) 2006 David Kocher. All rights reserved.
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

import ch.cyberduck.core.serializer.HostReaderFactory;
import ch.cyberduck.core.serializer.HostWriterFactory;
import ch.cyberduck.core.threading.DefaultMainAction;
import ch.cyberduck.ui.cocoa.CDMainApplication;

import org.apache.log4j.Logger;

import java.util.Timer;
import java.util.TimerTask;

/**
 * @version $Id$
 */
public class HostCollection extends BookmarkCollection {
    private static Logger log = Logger.getLogger(HostCollection.class);

    /**
     * Default bookmark file
     */
    private static HostCollection DEFAULT_COLLECTION = new HostCollection(
            new Local(Preferences.instance().getProperty("application.support.path"), "Favorites.plist")
    );

    /**
     * @return
     */
    public static HostCollection defaultCollection() {
        return DEFAULT_COLLECTION;
    }

    /**
     * @param file
     */
    public HostCollection(Local file) {
        this.setFile(file);
        this.load();
    }

    /**
     * The file to persist this collection in
     */
    protected Local file;

    /**
     * Will create the parent directory if missing
     *
     * @param file
     */
    protected void setFile(Local file) {
        this.file = file;
        this.file.getParent().mkdir(true);
    }

    /**
     * @return
     */
    public Local getFile() {
        return this.file;
    }

    @Override
    public synchronized Host get(int row) {
        return super.get(row);
    }

    /**
     * @param host
     * @return
     * @see Host
     */
    @Override
    public synchronized boolean add(Host host) {
        this.add(this.size(), host);
        return true;
    }

    /**
     * @param row
     * @param host
     * @see Host
     */
    @Override
    public synchronized void add(int row, Host host) {
        super.add(row, this.unique(host));
        this.sort();
        this.save();
    }

    /**
     * Ensures the bookmark nickname is unique
     *
     * @param host
     * @return
     */
    protected Host unique(Host host) {
        final String proposal = host.getNickname();
        for(int i = 1; this.contains(host) && !(this.get(this.indexOf(host)) == host); i++) {
            host.setNickname(proposal + " (" + i + ")");
        }
        return host;
    }

    /**
     * @param row
     * @return the element that was removed from the list.
     */
    @Override
    public synchronized Host remove(int row) {
        Host previous = super.remove(row);
        this.save();
        return previous;
    }

    protected void sort() {
        //
    }

    /**
     * Saves this collection of bookmarks in to a file to the users's application support directory
     * in a plist xml format
     */
    protected void save() {
        if(Preferences.instance().getBoolean("favorites.save")) {
            HostWriterFactory.instance().write(this, file);
        }
    }

    /**
     * Deserialize all the bookmarks saved previously in the users's application support directory
     */
    protected void load() {
        if(file.exists()) {
            log.info("Found Bookmarks file: " + file.getAbsolute());
            this.addAll(HostReaderFactory.instance().readCollection(file));
            this.sort();
        }
    }

    private Timer delayed = null;

    @Override
    public synchronized void collectionItemChanged(Host item) {
        if(null != delayed) {
            delayed.cancel();
        }
        delayed = new Timer();
        delayed.schedule(new TimerTask() {
            public void run() {
                CDMainApplication.invoke(new DefaultMainAction() {
                    public void run() {
                        save();
                    }
                });
            }
        }, 5000); // Delay to 5 seconds. When typing changes we don't have to save every iteration.
        super.collectionItemChanged(item);
    }
}