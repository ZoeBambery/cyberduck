package ch.cyberduck.ui.cocoa.delegate;

/*
 *  Copyright (c) 2007 David Kocher. All rights reserved.
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

import ch.cyberduck.ui.cocoa.ProxyController;
import ch.cyberduck.ui.cocoa.application.NSMenu;
import ch.cyberduck.ui.cocoa.application.NSMenuItem;

import org.apache.log4j.Logger;
import org.rococoa.cocoa.foundation.NSInteger;

/**
 * @version $Id$
 */
public abstract class AbstractMenuDelegate extends ProxyController implements NSMenu.Delegate {
    private static Logger log = Logger.getLogger(AbstractMenuDelegate.class);

    /**
     * Menu needs revalidation
     */
    private boolean update;

    public AbstractMenuDelegate() {
        this.setNeedsUpdate(true);
    }

    /**
     * Called to let you update a menu item before it is displayed. If your
     * numberOfItemsInMenu delegate method returns a positive value,
     * then your menuUpdateItemAtIndex method is called for each item in the menu.
     * You can then update the menu title, image, and so forth for the menu item.
     * Return true to continue the process. If you return false, your menuUpdateItemAtIndex
     * is not called again. In that case, it is your responsibility to trim any extra items from the menu.
     */
    public boolean menu_updateItem_atIndex_shouldCancel(NSMenu menu, NSMenuItem item, NSInteger index, boolean shouldCancel) {
        return this.menuUpdateItemAtIndex(menu, item, index, shouldCancel);
    }

    public boolean menuUpdateItemAtIndex(NSMenu menu, NSMenuItem item, NSInteger index, boolean shouldCancel) {
        if(log.isDebugEnabled()) {
            log.debug("menuUpdateItemAtIndex:" + index.intValue());
        }
        if(index.intValue() == this.numberOfItemsInMenu(menu).intValue() - 1) {
            this.setNeedsUpdate(false);
        }
        return !shouldCancel;
    }

    /**
     * Menu needs revalidation before being displayed the next time
     */
    protected void setNeedsUpdate(boolean u) {
        log.debug("setNeedsUpdate:" + u);
        update = u;
    }

    /**
     * @return
     */
    protected boolean isPopulated() {
        return !update;
    }

    public void menuWillOpen(NSMenu menu) {
//        if(log.isDebugEnabled()) {
//            log.debug("menuWillOpen:" + menu);
//        }
    }

    public void menuDidClose(NSMenu menu) {
//        if(log.isDebugEnabled()) {
//            log.debug("menuDidClose:" + menu);
//        }
    }
}