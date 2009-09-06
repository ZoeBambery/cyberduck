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
import ch.cyberduck.ui.cocoa.application.NSDraggingInfo;
import ch.cyberduck.ui.cocoa.application.NSPasteboard;
import ch.cyberduck.ui.cocoa.application.NSTableColumn;
import ch.cyberduck.ui.cocoa.application.NSTableView;
import ch.cyberduck.ui.cocoa.foundation.NSArray;
import ch.cyberduck.ui.cocoa.foundation.NSIndexSet;
import ch.cyberduck.ui.cocoa.foundation.NSObject;
import ch.cyberduck.ui.cocoa.foundation.NSString;

import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.rococoa.cocoa.foundation.NSInteger;
import org.rococoa.cocoa.foundation.NSUInteger;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * @version $Id$
 */
public class CDTransferTableDataSource extends CDListDataSource {
    private static Logger log = Logger.getLogger(CDTransferTableDataSource.class);

    public static final String PROGRESS_COLUMN = "PROGRESS";
    // virtual column to implement keyboard selection
    protected static final String TYPEAHEAD_COLUMN = "TYPEAHEAD";

    /**
     *
     */
    private final Map<Transfer, CDProgressController> controllers = new HashMap<Transfer, CDProgressController>();

    public CDTransferTableDataSource() {
        TransferCollection.instance().addListener(new AbstractCollectionListener<Transfer>() {
            @Override
            public void collectionItemAdded(Transfer item) {
                controllers.put(item, new CDProgressController(item));
            }

            @Override
            public void collectionItemRemoved(Transfer item) {
                final CDProgressController controller = controllers.remove(item);
                if(controller != null) {
                    controller.invalidate();
                }
            }
        });
    }

    /**
     *
     */
    private TransferFilter filter = new NullTransferFilter();

    /**
     * @param searchString
     */
    public void setFilter(final String searchString) {
        if(StringUtils.isBlank(searchString)) {
            // Revert to the default filter
            this.filter = new NullTransferFilter();
        }
        else {
            // Setting up a custom filter
            this.filter = new TransferFilter() {
                public boolean accept(Transfer transfer) {
                    // Match for pathnames and hostname
                    return transfer.getName().toLowerCase().contains(searchString.toLowerCase())
                            || transfer.getSession().getHost().getHostname().toLowerCase().contains(searchString.toLowerCase());
                }
            };
        }
    }

    /**
     * @return The filtered collection currently to be displayed within the constraints
     */
    protected Collection<Transfer> getSource() {
        if(null == filter) {
            return TransferCollection.instance();
        }
        Collection<Transfer> filtered = new Collection<Transfer>(TransferCollection.instance());
        for(Iterator<Transfer> i = filtered.iterator(); i.hasNext();) {
            if(!filter.accept(i.next())) {
                //temporarly remove the t from the collection
                i.remove();
            }
        }
        return filtered;
    }

    /**
     * @param view
     */
    public NSInteger numberOfRowsInTableView(NSTableView view) {
        return new NSInteger(this.getSource().size());
    }

    /**
     * @param view
     * @param tableColumn
     * @param row
     */
    public NSObject tableView_objectValueForTableColumn_row(NSTableView view, NSTableColumn tableColumn, NSInteger row) {
        final String identifier = tableColumn.identifier();
        if(identifier.equals(PROGRESS_COLUMN)) {
            return null;
        }
        if(identifier.equals(TYPEAHEAD_COLUMN)) {
            return NSString.stringWithString(this.getSource().get(row.intValue()).getName());
        }
        throw new IllegalArgumentException("Unknown identifier: " + identifier);
    }

    // ----------------------------------------------------------
    // Drop methods
    // ----------------------------------------------------------

    @Override
    public NSUInteger tableView_validateDrop_proposedRow_proposedDropOperation(NSTableView view, NSDraggingInfo draggingInfo, NSInteger row, NSUInteger operation) {
        log.debug("tableViewValidateDrop:row:" + row + ",operation:" + operation);
        if(draggingInfo.draggingPasteboard().availableTypeFromArray(NSArray.arrayWithObject(NSPasteboard.StringPboardType)) != null) {
            view.setDropRow(row, NSTableView.NSTableViewDropAbove);
            return NSDraggingInfo.NSDragOperationCopy;
        }
        if(!PathPasteboard.allPasteboards().isEmpty()) {
            view.setDropRow(row, NSTableView.NSTableViewDropAbove);
            return NSDraggingInfo.NSDragOperationCopy;
        }
        log.debug("tableViewValidateDrop:DragOperationNone");
        return NSDraggingInfo.NSDragOperationNone;
    }

    /**
     * Invoked by tableView when the mouse button is released over a table view that previously decided to allow a drop.
     *
     * @param draggingInfo contains details on this dragging operation.
     * @param row          The proposed location is row and action is operation.
     */
    @Override
    public boolean tableView_acceptDrop_row_dropOperation(NSTableView view, NSDraggingInfo draggingInfo, NSInteger row, NSUInteger operation) {
        if(draggingInfo.draggingPasteboard().availableTypeFromArray(NSArray.arrayWithObject(NSPasteboard.StringPboardType)) != null) {
            String droppedText = draggingInfo.draggingPasteboard().stringForType(NSPasteboard.StringPboardType);// get the data from paste board
            if(StringUtils.isNotBlank(droppedText)) {
                log.info("NSPasteboard.StringPboardType:" + droppedText);
                CDDownloadController c = new CDDownloadController(CDTransferController.instance(), droppedText);
                c.beginSheet();
                return true;
            }
            return false;
        }
        final Map<Host, PathPasteboard> boards = PathPasteboard.allPasteboards();
        if(!boards.isEmpty()) {
            for(PathPasteboard pasteboard : boards.values()) {
                TransferCollection.instance().add(row.intValue(), new DownloadTransfer(pasteboard.getFiles()));
                view.reloadData();
                view.selectRowIndexes(NSIndexSet.indexSetWithIndex(row), false);
                view.scrollRowToVisible(row);
            }
            boards.clear();
            return true;
        }
        return false;
    }

    /**
     * @param row
     * @return
     */
    public CDProgressController getController(int row) {
        return controllers.get(this.getSource().get(row));
    }

    /**
     * @param t
     * @return
     */
    public CDProgressController getController(Transfer t) {
        return controllers.get(t);
    }

    /**
     * @param row
     * @param highlighted
     */
    public void setHighlighted(int row, boolean highlighted) {
        this.getController(row).setHighlighted(highlighted);
    }
}