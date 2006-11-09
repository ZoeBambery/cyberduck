package ch.cyberduck.core.io;

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

import ch.cyberduck.core.Local;

import com.apple.cocoa.application.NSWorkspace;
import com.apple.cocoa.foundation.NSBundle;
import com.apple.cocoa.foundation.NSNotification;
import com.apple.cocoa.foundation.NSSelector;
import com.apple.cocoa.foundation.NSObject;

import org.apache.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;

public class FileWatcher extends NSObject {
    private static Logger log = Logger.getLogger(FileWatcher.class);

    private static final String UKKQueueFileRenamedNotification = "UKKQueueFileRenamedNotification";
    private static final String UKKQueueFileWrittenToNotification = "UKKQueueFileWrittenToNotification";
    private static final String UKKQueueFileDeletedNotification = "UKKQueueFileDeletedNotification";

    /**
     *
     */
    private final static Map instances = new HashMap();

    static {
        // Ensure native odb library is loaded
        try {
            NSBundle bundle = NSBundle.mainBundle();
            String lib = bundle.resourcePath() + "/Java/" + "libKQueue.dylib";
            log.info("Locating libKQueue.dylib at '" + lib + "'");
            System.load(lib);
        }
        catch(UnsatisfiedLinkError e) {
            log.error("Could not load the KQueue library:" + e.getMessage());
        }
    }

    public static FileWatcher instance(final Local path) {
        if(!instances.containsKey(path)) {
            instances.put(path, new FileWatcher(path));
        }
        return (FileWatcher)instances.get(path);
    }

    /**
     * The file to be watched
     */
    private Local file;

    /**
     * The listeners to get notified about file system changes
     */
    private List listeners = new ArrayList();

    /**
     * 
     * @param file
     */
    private FileWatcher(final Local file) {
        this.file = file;
    }

    public void fileWritten(NSNotification notification) {
        FileWatcherListener[] l = (FileWatcherListener[])listeners.toArray(new FileWatcherListener[]{});
        for(int i = 0; i < l.length; i++) {
            l[i].fileWritten(new Local((String) notification.object()));
        }
    }

    public void fileRenamed(NSNotification notification) {
        FileWatcherListener[] l = (FileWatcherListener[])listeners.toArray(new FileWatcherListener[]{});
        for(int i = 0; i < l.length; i++) {
            l[i].fileRenamed(new Local((String) notification.object()));
        }
    }

    public void fileDeleted(NSNotification notification) {
        FileWatcherListener[] l = (FileWatcherListener[])listeners.toArray(new FileWatcherListener[]{});
        for(int i = 0; i < l.length; i++) {
            l[i].fileDeleted(new Local((String) notification.object()));
        }
        removePath(file.getAbsolute());
    }

    public void watch(final FileWatcherListener listener) {
        this.listeners.add(listener);
        NSWorkspace.sharedWorkspace().notificationCenter().addObserver(
                this,
                new NSSelector("fileWritten", new Class[]{NSNotification.class}),
                UKKQueueFileWrittenToNotification,
                null);
        NSWorkspace.sharedWorkspace().notificationCenter().addObserver(
                this,
                new NSSelector("fileRenamed", new Class[]{NSNotification.class}),
                UKKQueueFileRenamedNotification,
                null);
        NSWorkspace.sharedWorkspace().notificationCenter().addObserver(
                this, new NSSelector("fileDeleted", new Class[]{NSNotification.class}),
                UKKQueueFileDeletedNotification,
                null);
        this.addPath(file.getAbsolute());
    }

    private native void addPath(String local);

    private native void removePath(String local);
}
