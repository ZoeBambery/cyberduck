package ch.cyberduck.ui.cocoa;

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

import ch.cyberduck.core.*;
import ch.cyberduck.core.i18n.Locale;
import ch.cyberduck.core.serializer.HostReaderFactory;
import ch.cyberduck.ui.cocoa.application.NSAlert;

/**
 * @version $Id$
 */
public class CDDotMacController extends CDController {

    private static boolean JNI_LOADED = false;

    private static boolean loadNative() {
        if(!JNI_LOADED) {
            JNI_LOADED = Native.load("DotMac");
        }
        return JNI_LOADED;
    }

    static {
        CDDotMacController.loadNative();
    }

    /**
     * @return Member name of the MobileMe account configured in System Preferences
     */
    public native String getAccountName();

    /**
     * @param path
     */
    private native void downloadBookmarks(String path);

    /**
     *
     */
    public void downloadBookmarks() {
        if(!CDDotMacController.loadNative()) {
            return;
        }
        final Local f = new Local(Preferences.instance().getProperty("tmp.dir"), "Favorites.plist");
        this.downloadBookmarks(f.getAbsolute());
        if(f.exists()) {
            final Collection<Host> collection = HostReaderFactory.instance().readCollection(f);
            for(Host bookmark : collection) {
                if(!HostCollection.defaultCollection().contains(bookmark)) {
                    final NSAlert alert = NSAlert.alert((bookmark).getNickname(),
                            Locale.localizedString("Add this bookmark to your existing bookmarks?", "IDisk"),
                            Locale.localizedString("Add", "IDisk"), //default
                            Locale.localizedString("Cancel"), //alternate
                            Locale.localizedString("Skip", "IDisk"));
                    int choice = alert.runModal(); //other
                    if(choice == CDSheetCallback.DEFAULT_OPTION) {
                        HostCollection.defaultCollection().add(bookmark);
                    }
                    if(choice == CDSheetCallback.OTHER_OPTION) {
                        return;
                    }
                }
            }
        }
        f.delete();
    }

    /**
     *
     */
    public native void uploadBookmarks();
}