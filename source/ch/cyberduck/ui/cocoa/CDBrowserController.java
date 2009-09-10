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
import ch.cyberduck.core.Collection;
import ch.cyberduck.core.i18n.Locale;
import ch.cyberduck.core.io.BandwidthThrottle;
import ch.cyberduck.core.sftp.SFTPSession;
import ch.cyberduck.core.ssl.SSLSession;
import ch.cyberduck.core.threading.BackgroundAction;
import ch.cyberduck.core.threading.BackgroundActionRegistry;
import ch.cyberduck.core.util.URLSchemeHandlerConfiguration;
import ch.cyberduck.ui.cocoa.application.*;
import ch.cyberduck.ui.cocoa.delegate.*;
import ch.cyberduck.ui.cocoa.foundation.*;
import ch.cyberduck.ui.cocoa.model.CDPathReference;
import ch.cyberduck.ui.cocoa.odb.Editor;
import ch.cyberduck.ui.cocoa.odb.EditorFactory;
import ch.cyberduck.ui.cocoa.quicklook.QLPreviewPanel;
import ch.cyberduck.ui.cocoa.quicklook.QLPreviewPanelController;
import ch.cyberduck.ui.cocoa.quicklook.QuickLookFactory;
import ch.cyberduck.ui.cocoa.threading.BrowserBackgroundAction;
import ch.cyberduck.ui.cocoa.threading.WindowMainAction;
import ch.cyberduck.ui.growl.Growl;

import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.rococoa.Foundation;
import org.rococoa.ID;
import org.rococoa.Rococoa;
import org.rococoa.Selector;
import org.rococoa.cocoa.CGFloat;
import org.rococoa.cocoa.foundation.NSInteger;
import org.rococoa.cocoa.foundation.NSUInteger;

import java.io.File;
import java.security.cert.X509Certificate;
import java.text.MessageFormat;
import java.util.*;

import com.sun.jna.ptr.PointerByReference;

/**
 * @version $Id$
 */
public class CDBrowserController extends CDWindowController implements NSToolbar.Delegate, QLPreviewPanelController {
    private static Logger log = Logger.getLogger(CDBrowserController.class);

    public CDBrowserController() {
        this.loadBundle();
    }

    @Override
    protected String getBundleName() {
        return "Browser";
    }

    public static void validateToolbarItems() {
        for(CDBrowserController controller : CDMainController.getBrowsers()) {
            controller.window().toolbar().validateVisibleItems();
        }
    }

    public static void updateBookmarkTableRowHeight() {
        for(CDBrowserController controller : CDMainController.getBrowsers()) {
            controller._updateBookmarkCell();
        }
    }

    public static void updateBrowserTableAttributes() {
        for(CDBrowserController controller : CDMainController.getBrowsers()) {
            controller._updateBrowserAttributes(controller.browserListView);
            controller._updateBrowserAttributes(controller.browserOutlineView);
        }
    }

    public static void updateBrowserTableColumns() {
        for(CDBrowserController controller : CDMainController.getBrowsers()) {
            controller._updateBrowserColumns(controller.browserListView);
            controller._updateBrowserColumns(controller.browserOutlineView);
        }
    }

    private NSToolbar toolbar;

    @Override
    public void awakeFromNib() {
        this._updateBrowserColumns(this.browserListView);
        this._updateBrowserColumns(this.browserOutlineView);

        if(Preferences.instance().getBoolean("browser.logDrawer.isOpen")) {
            this.logDrawer.open();
        }
        // Configure Toolbar
        this.toolbar = NSToolbar.toolbarWithIdentifier("Cyberduck Toolbar");
        this.toolbar.setDelegate((this.id()));
        this.toolbar.setAllowsUserCustomization(true);
        this.toolbar.setAutosavesConfiguration(true);
        this.window().setToolbar(toolbar);

        this.window().makeFirstResponder(this.quickConnectPopup);

        this.toggleBookmarks(true);

        if(this.getSelectedTabView() != TAB_BOOKMARKS) {
            this.browserSwitchClicked(Preferences.instance().getInteger("browser.view"));
        }

        this.validateNavigationButtons();

        super.awakeFromNib();
    }

    protected Comparator<Path> getComparator() {
        return this.getSelectedBrowserDelegate().getSortingComparator();
    }

    /**
     * Hide files beginning with '.'
     */
    private boolean showHiddenFiles;

    private PathFilter<Path> filenameFilter;

    {
        if(Preferences.instance().getBoolean("browser.showHidden")) {
            this.filenameFilter = new NullPathFilter<Path>();
            this.showHiddenFiles = true;
        }
        else {
            this.filenameFilter = new HiddenFilesPathFilter<Path>();
            this.showHiddenFiles = false;
        }
    }

    protected PathFilter<Path> getFileFilter() {
        return this.filenameFilter;
    }

    protected void setPathFilter(final String searchString) {
        log.debug("setPathFilter:" + searchString);
        if(StringUtils.isBlank(searchString)) {
            this.searchField.setStringValue("");
            // Revert to the last used default filter
            if(this.getShowHiddenFiles()) {
                this.filenameFilter = new NullPathFilter<Path>();
            }
            else {
                this.filenameFilter = new HiddenFilesPathFilter<Path>();
            }
        }
        else {
            // Setting up a custom filter for the directory listing
            this.filenameFilter = new PathFilter<Path>() {
                public boolean accept(Path file) {
                    if(file.getName().toLowerCase().indexOf(searchString.toLowerCase()) != -1) {
                        // Matching filename
                        return true;
                    }
                    if(file.attributes.isDirectory() && getSelectedBrowserView() == browserOutlineView) {
                        // #471. Expanded item childs may match search string
                        return file.isCached();
                    }
                    return false;
                }
            };
        }
        this.reloadData(true);
    }

    public void setShowHiddenFiles(boolean showHidden) {
        if(showHidden) {
            this.filenameFilter = new NullPathFilter<Path>();
            this.showHiddenFiles = true;
        }
        else {
            this.filenameFilter = new HiddenFilesPathFilter<Path>();
            this.showHiddenFiles = false;
        }
    }

    public boolean getShowHiddenFiles() {
        return this.showHiddenFiles;
    }

    /**
     * Marks the current browser as the first responder
     */
    private void getFocus() {
        if(this.getSelectedTabView() == TAB_BOOKMARKS) {
            if(this.isMounted()) {
                int row = this.bookmarkModel.getSource().indexOf(this.getSession().getHost());
                if(row != -1) {
                    this.bookmarkTable.selectRowIndexes(NSIndexSet.indexSetWithIndex(new NSInteger(row)), false);
                    this.bookmarkTable.scrollRowToVisible(new NSInteger(row));
                }
            }
            this.updateStatusLabel(this.bookmarkTable.numberOfRows() + " " + Locale.localizedString("Bookmarks"));
            this.window().makeFirstResponder(bookmarkTable);
        }
        else {
            if(this.isMounted()) {
                this.window().makeFirstResponder(this.getSelectedBrowserView());
            }
            else {
                this.window().makeFirstResponder(this.quickConnectPopup);
            }
            this.updateStatusLabel();
        }
    }

    /**
     * @param preserveSelection All selected files should be reselected after reloading the view
     * @pre Must always be invoked from the main interface thread
     */
    public void reloadData(final boolean preserveSelection) {
        if(preserveSelection) {
            //Remember the previously selected paths
            this.reloadData(this.getSelectedPaths());
        }
        else {
            this.reloadData(Collections.<Path>emptyList());
        }
    }

    /**
     * Make the broser reload its content. Will make use of the cache.
     *
     * @param selected The items to be selected
     * @see #setSelectedPaths(java.util.List)
     */
    protected void reloadData(final List<Path> selected) {
        log.debug("reloadData");
        // Tell the browser view to reload the data. This will request all paths from the browser model
        // which will refetch paths from the server marked as invalid.
        final NSTableView browser = this.getSelectedBrowserView();
        browser.reloadData();
        this.setSelectedPaths(selected);
    }

    /**
     * @param path
     * @param expand Expand the existing selection
     */
    private void selectRow(Path path, boolean expand) {
        log.debug("selectRow:" + path);
        final NSTableView browser = this.getSelectedBrowserView();
        if(this.getSelectedBrowserModel().contains(browser, path)) {
            this.selectRow(this.getSelectedBrowserModel().indexOf(browser, path), expand);
        }
    }

    /**
     * @param row
     * @param expand Expand the existing selection
     */
    private void selectRow(int row, boolean expand) {
        log.debug("selectRow:" + row);
        if(-1 == row) {
            return;
        }
        final NSTableView browser = this.getSelectedBrowserView();
        final NSInteger index = new NSInteger(row);
        browser.selectRowIndexes(NSIndexSet.indexSetWithIndex(index), expand);
        browser.scrollRowToVisible(index);
    }

    /**
     * @param selected
     */
    protected void setSelectedPath(Path selected) {
        List<Path> list = new Collection<Path>();
        list.add(selected);
        this.setSelectedPaths(list);
    }

    /**
     * @param selected
     */
    protected void setSelectedPaths(List<Path> selected) {
        log.debug("setSelectedPaths");
        this.deselectAll();
        if(!selected.isEmpty()) {
            switch(browserSwitchView.selectedSegment()) {
                case SWITCH_LIST_VIEW: {
                    //selection handling
                    for(Path path : selected) {
                        this.selectRow(path, true);
                    }
                    break;
                }
                case SWITCH_OUTLINE_VIEW: {
                    for(Path path : selected) {
                        final int row = browserOutlineView.rowForItem(path.<NSObject>getReference().unique()).intValue();
                        this.selectRow(row, true);
                    }
                    break;
                }
            }
        }
    }

    /**
     * @return The first selected path found or null if there is no selection
     */
    protected Path getSelectedPath() {
        List<Path> selected = this.getSelectedPaths();
        if(selected.size() > 0) {
            return selected.get(0);
        }
        return null;
    }

    /**
     * @return All selected paths or an empty list if there is no selection
     */
    protected Collection<Path> getSelectedPaths() {
        Collection<Path> selectedFiles = new Collection<Path>();
        if(this.isMounted()) {
            NSIndexSet iterator = this.getSelectedBrowserView().selectedRowIndexes();
            for(NSUInteger index = iterator.firstIndex(); index.longValue() != NSIndexSet.NSNotFound; index = iterator.indexGreaterThanIndex(index)) {
                if(index.intValue() == -1) {
                    break;
                }
                Path selected = this.pathAtRow(index.intValue());
                if(null == selected) {
                    break;
                }
                selectedFiles.add(selected);
            }
        }
        return selectedFiles;
    }

    protected int getSelectionCount() {
        return this.getSelectedBrowserView().numberOfSelectedRows().intValue();
    }

    private void deselectAll() {
        log.debug("deselectAll");
        final NSTableView browser = this.getSelectedBrowserView();
        if(null == browser) {
            return;
        }
        browser.deselectAll(null);
    }

    private Path pathAtRow(int row) {
        switch(this.browserSwitchView.selectedSegment()) {
            case SWITCH_LIST_VIEW: {
                final AttributedList<Path> childs = this.browserListModel.childs(this.workdir());
                if(row < childs.size()) {
                    return childs.get(row);
                }
                break;
            }
            case SWITCH_OUTLINE_VIEW: {
                if(row < this.browserOutlineView.numberOfRows().intValue()) {
                    return this.lookup(new CDPathReference(this.browserOutlineView.itemAtRow(new NSInteger(row))));
                }
                break;
            }
        }
        log.warn("No item at row:" + row);
        return null;
    }

    @Override
    public void setWindow(NSWindow window) {
        window.setTitle(NSBundle.mainBundle().infoDictionary().objectForKey("CFBundleName").toString());
        window.setMiniwindowImage(NSImage.imageNamed("cyberduck-document.icns"));
        window.setMovableByWindowBackground(true);
        super.setWindow(window);
    }

    private CDTranscriptController transcript;

    @Outlet
    private NSDrawer logDrawer;

    public void drawerWillOpen(NSNotification notification) {
        logDrawer.setContentSize(new NSSize(
                logDrawer.contentSize().width.doubleValue(),
                Preferences.instance().getDouble("browser.logDrawer.size.height")
        ));
    }

    public void drawerDidOpen(NSNotification notification) {
        Preferences.instance().setProperty("browser.logDrawer.isOpen", true);
    }

    public void drawerWillClose(NSNotification notification) {
        Preferences.instance().setProperty("browser.logDrawer.size.height",
                logDrawer.contentSize().height.intValue());
    }

    public void drawerDidClose(NSNotification notification) {
        Preferences.instance().setProperty("browser.logDrawer.isOpen", false);
    }

    public void setLogDrawer(NSDrawer logDrawer) {
        this.logDrawer = logDrawer;
        this.transcript = new CDTranscriptController();
        this.logDrawer.setContentView(this.transcript.getLogView());
        NSNotificationCenter.defaultCenter().addObserver(this.id(),
                Foundation.selector("drawerWillOpen:"),
                NSDrawer.DrawerWillOpenNotification,
                this.logDrawer);
        NSNotificationCenter.defaultCenter().addObserver(this.id(),
                Foundation.selector("drawerDidOpen:"),
                NSDrawer.DrawerDidOpenNotification,
                this.logDrawer);
        NSNotificationCenter.defaultCenter().addObserver(this.id(),
                Foundation.selector("drawerWillClose:"),
                NSDrawer.DrawerWillCloseNotification,
                this.logDrawer);
        NSNotificationCenter.defaultCenter().addObserver(this.id(),
                Foundation.selector("drawerDidClose:"),
                NSDrawer.DrawerDidCloseNotification,
                this.logDrawer);
    }

    private static final int TAB_BOOKMARKS = 0;
    private static final int TAB_LIST_VIEW = 1;
    private static final int TAB_OUTLINE_VIEW = 2;

    /**
     * @return
     */
    private int getSelectedTabView() {
        return this.browserTabView.indexOfTabViewItem(this.browserTabView.selectedTabViewItem());
    }

    private NSTabView browserTabView;

    public void setBrowserTabView(NSTabView browserTabView) {
        this.browserTabView = browserTabView;
    }

    /**
     * @return The currently selected browser view (which is either an outlineview or a plain tableview)
     */
    public NSTableView getSelectedBrowserView() {
        switch(this.browserSwitchView.selectedSegment()) {
            case SWITCH_LIST_VIEW: {
                return this.browserListView;
            }
            case SWITCH_OUTLINE_VIEW: {
                return this.browserOutlineView;
            }
        }
        log.fatal("No selected brower view");
        return null;
    }

    /**
     * @return The datasource of the currently selected browser view
     */
    public CDBrowserTableDataSource getSelectedBrowserModel() {
        switch(this.browserSwitchView.selectedSegment()) {
            case SWITCH_LIST_VIEW: {
                return this.browserListModel;
            }
            case SWITCH_OUTLINE_VIEW: {
                return this.browserOutlineModel;
            }
        }
        log.fatal("No selected brower view");
        return null;
    }

    public AbstractBrowserTableDelegate<Path> getSelectedBrowserDelegate() {
        switch(this.browserSwitchView.selectedSegment()) {
            case SWITCH_LIST_VIEW: {
                return this.browserListViewDelegate;
            }
            case SWITCH_OUTLINE_VIEW: {
                return this.browserOutlineViewDelegate;
            }
        }
        log.fatal("No selected brower view");
        return null;
    }

    @Outlet
    private NSMenu editMenu;
    private EditMenuDelegate editMenuDelegate;

    public void setEditMenu(NSMenu editMenu) {
        this.editMenu = editMenu;
        this.editMenuDelegate = new EditMenuDelegate();
        this.editMenu.setDelegate(editMenuDelegate.id());
    }

    @Outlet
    private NSMenu archiveMenu;
    private ArchiveMenuDelegate archiveMenuDelegate;

    public void setArchiveMenu(NSMenu archiveMenu) {
        this.archiveMenu = archiveMenu;
        this.archiveMenuDelegate = new ArchiveMenuDelegate();
        this.archiveMenu.setDelegate(archiveMenuDelegate.id());
    }

    @Outlet
    private NSButton bonjourButton;

    public void setBonjourButton(NSButton bonjourButton) {
        this.bonjourButton = bonjourButton;
        this.bonjourButton.setImage(CDIconCache.instance().iconForName("rendezvous", 16));
        this.setRecessedBezelStyle(this.bonjourButton);
        this.bonjourButton.setTarget(this.id());
        this.bonjourButton.setAction(Foundation.selector("bookmarkButtonClicked:"));
    }

    @Outlet
    private NSButton historyButton;

    public void setHistoryButton(NSButton historyButton) {
        this.historyButton = historyButton;
        this.historyButton.setImage(CDIconCache.instance().iconForName("history", 16));
        this.setRecessedBezelStyle(this.historyButton);
        this.historyButton.setTarget(this.id());
        this.historyButton.setAction(Foundation.selector("bookmarkButtonClicked:"));
    }

    @Outlet
    private NSButton bookmarkButton;

    public void setBookmarkButton(NSButton bookmarkButton) {
        this.bookmarkButton = bookmarkButton;
        this.bookmarkButton.setImage(CDIconCache.instance().iconForName("bookmarks", 20, 16));
        this.setRecessedBezelStyle(this.bookmarkButton);
        this.bookmarkButton.setTarget(this.id());
        this.bookmarkButton.setAction(Foundation.selector("bookmarkButtonClicked:"));
        this.bookmarkButton.setState(NSCell.NSOnState); // Set as default selected bookmark source
    }

    public void bookmarkButtonClicked(final NSButton sender) {
        if(sender != bonjourButton) {
            bonjourButton.setState(NSCell.NSOffState);
        }
        if(sender != historyButton) {
            historyButton.setState(NSCell.NSOffState);
        }
        if(sender != bookmarkButton) {
            bookmarkButton.setState(NSCell.NSOffState);
        }
        sender.setState(NSCell.NSOnState);

        this.updateBookmarkSource();
    }

    private void setRecessedBezelStyle(final NSButton b) {
        b.setBezelStyle(NSButton.NSRecessedBezelStyle);
        b.setButtonType(NSButton.NSMomentaryPushButtonButton);
        b.setImagePosition(NSCell.NSImageLeft);
        b.setFont(NSFont.boldSystemFontOfSize(11f));
        b.setShowsBorderOnlyWhileMouseInside(true);
        b.sizeToFit();
    }

    private void updateBookmarkSource() {
        if(bonjourButton.state() == NSCell.NSOnState) {
            bookmarkModel.setSource(RendezvousCollection.defaultCollection());
        }
        else if(historyButton.state() == NSCell.NSOnState) {
            bookmarkModel.setSource(HistoryCollection.defaultCollection());
        }
        else if(bookmarkButton.state() == NSCell.NSOnState) {
            bookmarkModel.setSource(HostCollection.defaultCollection());
        }
        bookmarkTableDelegate.selectionDidChange(null);
        this.setBookmarkFilter(null);
        bookmarkTable.deselectAll(null);
        bookmarkTable.reloadData();
        this.getFocus();
    }

    private NSSegmentedControl bookmarkSwitchView;

    private static final int SWITCH_BOOKMARK_VIEW = 0;

    public void setBookmarkSwitchView(NSSegmentedControl bookmarkSwitchView) {
        this.bookmarkSwitchView = bookmarkSwitchView;
        this.bookmarkSwitchView.setSegmentCount(1);
        final NSImage image = NSImage.imageNamed("bookmarks.tiff");
        this.bookmarkSwitchView.setImage(image, SWITCH_BOOKMARK_VIEW);
        final NSSegmentedCell cell = Rococoa.cast(this.bookmarkSwitchView.cell(), NSSegmentedCell.class);
        cell.setTrackingMode(NSSegmentedCell.NSSegmentSwitchTrackingSelectAny);
        cell.setControlSize(NSCell.NSRegularControlSize);
        this.bookmarkSwitchView.setTarget(this.id());
        this.bookmarkSwitchView.setAction(Foundation.selector("bookmarkSwitchClicked:"));
        this.bookmarkSwitchView.setSelectedSegment(SWITCH_BOOKMARK_VIEW);
    }

    public void bookmarkSwitchClicked(final ID sender) {
        this.toggleBookmarks(this.getSelectedTabView() != TAB_BOOKMARKS);
    }

    /**
     * @param open Should open the bookmarks
     */
    public void toggleBookmarks(final boolean open) {
        log.debug("bookmarkSwitchClicked:" + open);
        this.bookmarkSwitchView.setSelected_forSegment(open, SWITCH_BOOKMARK_VIEW);
        if(open) {
            // Display bookmarks
            this.browserTabView.selectTabViewItemAtIndex(TAB_BOOKMARKS);
            this.updateBookmarkSource();
        }
        else {
            this.setBookmarkFilter(null);
            this.selectBrowser(Preferences.instance().getInteger("browser.view"));
        }
        this.getFocus();
        this.validateNavigationButtons();
    }

    private NSSegmentedControl browserSwitchView;

    private static final int SWITCH_LIST_VIEW = 0;
    private static final int SWITCH_OUTLINE_VIEW = 1;

    public void setBrowserSwitchView(NSSegmentedControl browserSwitchView) {
        this.browserSwitchView = browserSwitchView;
        this.browserSwitchView.setSegmentCount(2); // list, outline
        final NSImage list = NSImage.imageNamed("list.tiff");
        list.setTemplate(true);
        this.browserSwitchView.setImage(list, SWITCH_LIST_VIEW);
        final NSImage outline = NSImage.imageNamed("outline.tiff");
        outline.setTemplate(true);
        this.browserSwitchView.setImage(outline, SWITCH_OUTLINE_VIEW);
        this.browserSwitchView.setTarget(this.id());
        this.browserSwitchView.setAction(Foundation.selector("browserSwitchButtonClicked:"));
        final NSSegmentedCell cell = Rococoa.cast(this.browserSwitchView.cell(), NSSegmentedCell.class);
        cell.setTrackingMode(NSSegmentedCell.NSSegmentSwitchTrackingSelectOne);
        cell.setControlSize(NSCell.NSRegularControlSize);
        this.browserSwitchView.setSelectedSegment(Preferences.instance().getInteger("browser.view"));
    }

    public void browserSwitchButtonClicked(final NSSegmentedControl sender) {
        this.browserSwitchClicked(sender.selectedSegment());
    }

    public void browserSwitchMenuClicked(final NSMenuItem sender) {
        this.browserSwitchView.setSelectedSegment(sender.tag());
        this.browserSwitchClicked(sender.tag());
    }

    private void browserSwitchClicked(final int selected) {
        // Close bookmarks
        this.toggleBookmarks(false);
        // Highlight selected browser view
        this.selectBrowser(selected);
        this.reloadData(false);
        this.getFocus();
        // Save selected browser view
        Preferences.instance().setProperty("browser.view", selected);
    }

    private void selectBrowser(int selected) {
        this.browserSwitchView.setSelectedSegment(selected);
        switch(selected) {
            case SWITCH_LIST_VIEW:
                this.browserTabView.selectTabViewItemAtIndex(TAB_LIST_VIEW);
                break;
            case SWITCH_OUTLINE_VIEW:
                this.browserTabView.selectTabViewItemAtIndex(TAB_OUTLINE_VIEW);
                break;
        }
    }

    private abstract class AbstractBrowserOutlineViewDelegate<E> extends AbstractBrowserTableDelegate<E>
            implements NSOutlineView.Delegate {
    }

    private abstract class AbstractBrowserListViewDelegate<E> extends AbstractBrowserTableDelegate<E>
            implements NSTableView.Delegate {
    }

    private abstract class AbstractBrowserTableDelegate<E> extends CDAbstractPathTableDelegate {

        public AbstractBrowserTableDelegate() {
            CDBrowserController.this.addListener(new CDWindowListener() {
                public void windowWillClose() {
                    if(QuickLookFactory.instance().isAvailable()) {
                        if(QuickLookFactory.instance().isOpen()) {
                            QuickLookFactory.instance().close();
                        }
                    }
                }
            });
        }

        @Override
        public boolean isColumnEditable(NSTableColumn column) {
            if(Preferences.instance().getBoolean("browser.editable")) {
                if(column.identifier().equals(CDBrowserTableDataSource.FILENAME_COLUMN)) {
                    Path selected = getSelectedPath();
                    if(null == selected) {
                        return false;
                    }
                    return true;
                }
            }
            return false;
        }

        public void tableRowDoubleClicked(final ID sender) {
            CDBrowserController.this.insideButtonClicked(sender);
        }

        public void spaceKeyPressed(final ID sender) {
            if(QuickLookFactory.instance().isAvailable()) {
                if(QuickLookFactory.instance().isOpen()) {
                    QuickLookFactory.instance().close();
                }
                else {
                    this.updateQuickLookSelection(
                            CDBrowserController.this.getSelectedPaths()
                    );
                }
            }
        }

        /**
         * @param selected
         */
        private void updateQuickLookSelection(final Collection<Path> selected) {
            if(QuickLookFactory.instance().isAvailable()) {
                final Collection<Path> downloads = new Collection<Path>();
                for(Path path : selected) {
                    if(!path.attributes.isFile()) {
                        continue;
                    }
                    final Local folder = LocalFactory.createLocal(new File(Preferences.instance().getProperty("tmp.dir"),
                            path.getParent().getAbsolute()));
                    folder.mkdir(true);
                    path.setLocal(LocalFactory.createLocal(folder, path.getName()));
                    downloads.add(path);
                }
                if(downloads.size() > 0) {
                    background(new BrowserBackgroundAction(CDBrowserController.this) {
                        public void run() {
                            for(Path download : downloads) {
                                if(this.isCanceled()) {
                                    break;
                                }
                                if(download.getLocal().attributes.getSize() != download.attributes.getSize()) {
                                    download.download(true);
                                }
                            }
                        }

                        public void cleanup() {
                            final Collection<Local> previews = new Collection<Local>() {
                                @Override
                                public void collectionItemRemoved(Local o) {
                                    super.collectionItemRemoved(o);
                                    (o).delete(false);
                                }
                            };
                            for(Path download : downloads) {
                                if(download.getLocal().attributes.getSize() == download.attributes.getSize()) {
                                    previews.add(download.getLocal());
                                }
                            }
                            if(previews.isEmpty()) {
                                return;
                            }
                            // Change files in Quick Look
                            QuickLookFactory.instance().select(previews);
                            // Open Quick Look Preview Panel
                            QuickLookFactory.instance().open();
                            // Revert status label
                            CDBrowserController.this.updateStatusLabel();
                            // Restore the focus to our window to demo the selection changing, scrolling
                            // (left/right) and closing (space) functionality
                            CDBrowserController.this.window().makeKeyWindow();
                        }

                        @Override
                        public String getActivity() {
                            return Locale.localizedString("Quick Look", "Status");
                        }
                    });
                }
            }
        }

        public void deleteKeyPressed(final ID sender) {
            CDBrowserController.this.deleteFileButtonClicked(sender);
        }

        public void tableColumnClicked(NSTableView view, NSTableColumn tableColumn) {
            List<Path> selected = CDBrowserController.this.getSelectedPaths();
            if(this.selectedColumnIdentifier().equals(tableColumn.identifier())) {
                this.setSortedAscending(!this.isSortedAscending());
            }
            else {
                // Remove sorting indicator on previously selected column
                this.setBrowserColumnSortingIndicator(null, this.selectedColumnIdentifier());
                // Set the newly selected column
                this.setSelectedColumn(tableColumn);
            }
            this.setBrowserColumnSortingIndicator(
                    this.isSortedAscending() ?
                            NSImage.imageNamed("NSAscendingSortIndicator") :
                            NSImage.imageNamed("NSDescendingSortIndicator"),
                    tableColumn.identifier());
            reloadData(selected);
        }

        public void selectionDidChange(NSNotification notification) {
            final Collection<Path> selected = getSelectedPaths();
            if(1 == selected.size()) {
                final Path p = selected.get(0);
                if(p.attributes.isFile()) {
                    EditorFactory.setSelectedEditor(EditorFactory.editorBundleIdentifierForFile(
                            p.getLocal()));
                }
            }
            if(Preferences.instance().getBoolean("browser.info.isInspector")) {
                if(inspector != null && inspector.isVisible()) {
                    if(selected.size() > 0) {
                        background(new BrowserBackgroundAction(CDBrowserController.this) {
                            public void run() {
                                for(Path p : selected) {
                                    if(this.isCanceled()) {
                                        break;
                                    }
                                    if(p.attributes.getPermission() == null) {
                                        p.readPermission();
                                    }
                                }
                            }

                            public void cleanup() {
                                if(inspector != null) {
                                    inspector.setFiles(selected);
                                }
                            }
                        });
                    }
                }
            }
            if(QuickLookFactory.instance().isOpen()) {
                this.updateQuickLookSelection(selected);
            }
        }

        private void setBrowserColumnSortingIndicator(NSImage image, String columnIdentifier) {
            if(browserListView.tableColumnWithIdentifier(columnIdentifier) != null) {
                browserListView.setIndicatorImage_inTableColumn(image, browserListView.tableColumnWithIdentifier(columnIdentifier));
            }
            if(browserOutlineView.tableColumnWithIdentifier(columnIdentifier) != null) {
                browserOutlineView.setIndicatorImage_inTableColumn(image, browserOutlineView.tableColumnWithIdentifier(columnIdentifier));
            }
        }
    }

    /**
     * QuickLook support for 10.6+
     *
     * @param panel The Preview Panel looking for a controller.
     * @return
     * @ Sent to each object in the responder chain to find a controller.
     */
    public boolean acceptsPreviewPanelControl(QLPreviewPanel panel) {
        log.debug("acceptsPreviewPanelControl");
        return true;
    }

    /**
     * QuickLook support for 10.6+
     * The receiver should setup the preview panel (data source, delegate, binding, etc.) here.
     *
     * @param panel The Preview Panel the receiver will control.
     * @ Sent to the object taking control of the Preview Panel.
     */
    public void beginPreviewPanelControl(QLPreviewPanel panel) {
        log.debug("beginPreviewPanelControl");
        QuickLookFactory.instance().willBeginQuickLook();
    }

    /**
     * QuickLook support for 10.6+
     * The receiver should unsetup the preview panel (data source, delegate, binding, etc.) here.
     *
     * @param panel The Preview Panel that the receiver will stop controlling.
     * @ Sent to the object in control of the Preview Panel just before stopping its control.
     */
    public void endPreviewPanelControl(QLPreviewPanel panel) {
        log.debug("endPreviewPanelControl");
        QuickLookFactory.instance().didEndQuickLook();
    }

    private CDBrowserOutlineViewModel browserOutlineModel;
    @Outlet
    private NSOutlineView browserOutlineView;
    private AbstractBrowserTableDelegate<Path> browserOutlineViewDelegate;

    public void setBrowserOutlineView(NSOutlineView view) {
        browserOutlineView = view;
        // receive drag events from types
        browserOutlineView.registerForDraggedTypes(NSArray.arrayWithObjects(
                NSPasteboard.URLPboardType,
                NSPasteboard.FilenamesPboardType, //accept files dragged from the Finder for uploading
                NSPasteboard.FilesPromisePboardType //accept file promises made myself but then interpret them as TransferPasteboardType
        ));

        // setting appearance attributes
        final NSLayoutManager l = NSLayoutManager.layoutManager();
        browserOutlineView.setRowHeight(new CGFloat(l.defaultLineHeightForFont(
                NSFont.systemFontOfSize(Preferences.instance().getFloat("browser.font.size"))).intValue() + 2));
        this._updateBrowserAttributes(browserOutlineView);
        // selection properties
        browserOutlineView.setAllowsMultipleSelection(true);
        browserOutlineView.setAllowsEmptySelection(true);
        browserOutlineView.setAllowsColumnResizing(true);
        browserOutlineView.setAllowsColumnSelection(false);
        browserOutlineView.setAllowsColumnReordering(true);

        browserOutlineView.setDataSource((browserOutlineModel = new CDBrowserOutlineViewModel(this)).id());
        browserOutlineView.setDelegate((browserOutlineViewDelegate = new AbstractBrowserOutlineViewDelegate<Path>() {
            public void enterKeyPressed(final ID sender) {
                if(Preferences.instance().getBoolean("browser.enterkey.rename")) {
                    if(browserOutlineView.numberOfSelectedRows().intValue() == 1) {
                        browserOutlineView.editRow(
                                browserOutlineView.columnWithIdentifier(CDBrowserTableDataSource.FILENAME_COLUMN),
                                browserOutlineView.selectedRow(), true);
                    }
                }
                else {
                    this.tableRowDoubleClicked(sender);
                }
            }

            /**
             * @see NSOutlineView.Delegate
             */
            public void outlineView_willDisplayCell_forTableColumn_item(NSOutlineView view, NSTextFieldCell cell,
                                                                        NSTableColumn tableColumn, NSObject item) {
                if(tableColumn.identifier().equals(CDBrowserTableDataSource.FILENAME_COLUMN)) {
                    final Path path = lookup(new CDPathReference(item));
                    if(null == path) {
                        return;
                    }
                    cell.setEditable(path.isRenameSupported());
                    (Rococoa.cast(cell, CDOutlineCell.class)).setIcon(browserOutlineModel.iconForPath(path));
                }
                if(!CDBrowserController.this.isConnected()) {
                    cell.setTextColor(NSColor.disabledControlTextColor());
                }
                else {
                    cell.setTextColor(NSColor.controlTextColor());
                }
            }

            /**
             * @see NSOutlineView.Delegate
             */
            public boolean outlineView_shouldExpandItem(final NSOutlineView view, final NSObject item) {
                NSEvent event = NSApplication.sharedApplication().currentEvent();
                if(event != null) {
                    if(NSEvent.NSLeftMouseDragged == event.type()) {
                        final int draggingColumn = view.columnAtPoint(view.convertPoint_fromView(event.locationInWindow(), null)).intValue();
                        if(draggingColumn != 0) {
                            log.debug("Returning false to #outlineViewShouldExpandItem for column:" + draggingColumn);
                            // See ticket #60
                            return false;
                        }
                        if(!Preferences.instance().getBoolean("browser.view.autoexpand")) {
                            log.debug("Returning false to #outlineViewShouldExpandItem while dragging because browser.view.autoexpand == false");
                            // See tickets #98 and #633
                            return false;
                        }
                    }
                }
                return true;
            }

            /**
             * @see NSOutlineView.Delegate
             */
            public void outlineViewItemDidExpand(NSNotification notification) {
                updateStatusLabel();
            }

            /**
             * @see NSOutlineView.Delegate
             */
            public void outlineViewItemDidCollapse(NSNotification notification) {
                updateStatusLabel();
            }
        }).id());
        {
            NSTableColumn c = browserOutlineColumnsFactory.create(CDBrowserTableDataSource.FILENAME_COLUMN);
            c.headerCell().setStringValue(Locale.localizedString("Filename"));
            c.setMinWidth(new CGFloat(100));
            c.setWidth(new CGFloat(250));
            c.setMaxWidth(new CGFloat(1000));
            c.setResizingMask(NSTableColumn.NSTableColumnAutoresizingMask | NSTableColumn.NSTableColumnUserResizingMask);
            final NSTextFieldCell cell = outlineCellPrototype;
            {
                cell.setTarget(browserOutlineView.target());
                cell.setAction(browserOutlineView.action());
            }
            c.setDataCell(cell);
            this.browserOutlineView.addTableColumn(c);
            this.browserOutlineView.setOutlineTableColumn(c);
        }
    }

    private CDBrowserListViewModel browserListModel;
    @Outlet
    private NSTableView browserListView;
    private AbstractBrowserTableDelegate<Path> browserListViewDelegate;

    public void setBrowserListView(NSTableView view) {
        browserListView = view;
        // receive drag events from types
        browserListView.registerForDraggedTypes(NSArray.arrayWithObjects(
                NSPasteboard.URLPboardType,
                NSPasteboard.FilenamesPboardType, //accept files dragged from the Finder for uploading
                NSPasteboard.FilesPromisePboardType //accept file promises made myself but then interpret them as TransferPasteboardType
        ));

        // setting appearance attributes
        final NSLayoutManager l = NSLayoutManager.layoutManager();
        browserListView.setRowHeight(new CGFloat(l.defaultLineHeightForFont(
                NSFont.systemFontOfSize(Preferences.instance().getFloat("browser.font.size"))).intValue() + 2));
        this._updateBrowserAttributes(browserListView);
        // selection properties
        browserListView.setAllowsMultipleSelection(true);
        browserListView.setAllowsEmptySelection(true);
        browserListView.setAllowsColumnResizing(true);
        browserListView.setAllowsColumnSelection(false);
        browserListView.setAllowsColumnReordering(true);

        browserListView.setDataSource((browserListModel = new CDBrowserListViewModel(this)).id());
        browserListView.setDelegate((browserListViewDelegate = new AbstractBrowserListViewDelegate<Path>() {
            public void enterKeyPressed(final ID sender) {
                if(Preferences.instance().getBoolean("browser.enterkey.rename")) {
                    if(browserListView.numberOfSelectedRows().intValue() == 1) {
                        browserListView.editRow(
                                browserListView.columnWithIdentifier(CDBrowserTableDataSource.FILENAME_COLUMN),
                                browserListView.selectedRow(), true);
                    }
                }
                else {
                    this.tableRowDoubleClicked(sender);
                }
            }

            public void tableView_willDisplayCell_forTableColumn_row(NSTableView view, NSTextFieldCell cell, NSTableColumn tableColumn, NSInteger row) {
                final String identifier = tableColumn.identifier();
                if(identifier.equals(CDBrowserTableDataSource.FILENAME_COLUMN)) {
                    final Path item = browserListModel.childs(CDBrowserController.this.workdir()).get(row.intValue());
                    cell.setEditable(item.isRenameSupported());
                }
                if(cell.isKindOfClass(Foundation.getClass(NSTextFieldCell.class.getSimpleName()))) {
                    if(!CDBrowserController.this.isConnected()) {// || CDBrowserController.this.activityRunning) {
                        cell.setTextColor(NSColor.disabledControlTextColor());
                    }
                    else {
                        cell.setTextColor(NSColor.controlTextColor());
                    }
                }
            }
        }).id());
        {
            NSTableColumn c = browserListColumnsFactory.create(CDBrowserTableDataSource.ICON_COLUMN);
            c.headerCell().setStringValue("");
            c.setMinWidth((20));
            c.setWidth((20));
            c.setMaxWidth((20));
            c.setResizingMask(NSTableColumn.NSTableColumnAutoresizingMask);
            c.setDataCell(imageCellPrototype);
            c.dataCell().setAlignment(NSText.NSCenterTextAlignment);
            browserListView.addTableColumn(c);
        }
        {
            NSTableColumn c = browserListColumnsFactory.create(CDBrowserTableDataSource.FILENAME_COLUMN);
            c.headerCell().setStringValue(Locale.localizedString("Filename"));
            c.setMinWidth((100));
            c.setWidth((250));
            c.setMaxWidth((1000));
            c.setResizingMask(NSTableColumn.NSTableColumnAutoresizingMask | NSTableColumn.NSTableColumnUserResizingMask);
            NSTextFieldCell cell = filenameCellPrototype;
            {
                cell.setEditable(true); //to enable inline renaming of files
                cell.setTarget(view.target());
                cell.setAction(view.action());
            }
            c.setDataCell(cell);
            this.browserListView.addTableColumn(c);
        }
    }

    protected void _updateBrowserAttributes(NSTableView tableView) {
        tableView.setUsesAlternatingRowBackgroundColors(Preferences.instance().getBoolean("browser.alternatingRows"));
        if(Preferences.instance().getBoolean("browser.horizontalLines") && Preferences.instance().getBoolean("browser.verticalLines")) {
            tableView.setGridStyleMask(new NSUInteger(NSTableView.NSTableViewSolidHorizontalGridLineMask.intValue() | NSTableView.NSTableViewSolidVerticalGridLineMask.intValue()));
        }
        else if(Preferences.instance().getBoolean("browser.verticalLines")) {
            tableView.setGridStyleMask(NSTableView.NSTableViewSolidVerticalGridLineMask);
        }
        else if(Preferences.instance().getBoolean("browser.horizontalLines")) {
            tableView.setGridStyleMask(NSTableView.NSTableViewSolidHorizontalGridLineMask);
        }
        else {
            tableView.setGridStyleMask(NSTableView.NSTableViewGridNone);
        }
    }

    protected void _updateBookmarkCell() {
        final int size = Preferences.instance().getInteger("bookmark.icon.size");
        if(CDBookmarkCell.SMALL_BOOKMARK_SIZE == size) {
            this.bookmarkTable.setRowHeight(new CGFloat(18));
        }
        if(CDBookmarkCell.MEDIUM_BOOKMARK_SIZE == size) {
            this.bookmarkTable.setRowHeight(new CGFloat(45));
        }
        if(CDBookmarkCell.LARGE_BOOKMARK_SIZE == size) {
            this.bookmarkTable.setRowHeight(new CGFloat(70));
        }
        final double width = size * 1.5;
        final NSTableColumn c = this.bookmarkTable.tableColumnWithIdentifier(CDBookmarkTableDataSource.ICON_COLUMN);
        c.setMinWidth(width);
        c.setMaxWidth(width);
        c.setWidth(width);
    }

    private final NSTextFieldCell outlineCellPrototype = CDOutlineCell.outlineCell();
    private final NSImageCell imageCellPrototype = NSImageCell.imageCell();
    private final NSTextFieldCell textCellPrototype = NSTextFieldCell.textFieldCell();
    private final NSTextFieldCell filenameCellPrototype = NSTextFieldCell.textFieldCell();

    private final TableColumnFactory browserListColumnsFactory = new TableColumnFactory();
    private final TableColumnFactory browserOutlineColumnsFactory = new TableColumnFactory();
    private final TableColumnFactory bookmarkTableColumnFactory = new TableColumnFactory();

    private class TableColumnFactory extends HashMap<String, NSTableColumn> {
        private NSTableColumn create(String identifier) {
            if(!this.containsKey(identifier)) {
                this.put(identifier, NSTableColumn.tableColumnWithIdentifier(identifier));
            }
            return this.get(identifier);
        }
    }

    protected void _updateBrowserColumns(NSTableView table) {
        table.removeTableColumn(table.tableColumnWithIdentifier(CDBrowserTableDataSource.SIZE_COLUMN));
        if(Preferences.instance().getBoolean("browser.columnSize")) {
            NSTableColumn c = browserListColumnsFactory.create(CDBrowserTableDataSource.SIZE_COLUMN);
            c.headerCell().setStringValue(Locale.localizedString("Size"));
            c.setMinWidth((50f));
            c.setWidth((80f));
            c.setMaxWidth((150f));
            c.setResizingMask(NSTableColumn.NSTableColumnAutoresizingMask | NSTableColumn.NSTableColumnUserResizingMask);
            c.setDataCell(textCellPrototype);
            table.addTableColumn(c);
        }
        table.removeTableColumn(table.tableColumnWithIdentifier(CDBrowserTableDataSource.MODIFIED_COLUMN));
        if(Preferences.instance().getBoolean("browser.columnModification")) {
            NSTableColumn c = browserListColumnsFactory.create(CDBrowserTableDataSource.MODIFIED_COLUMN);
            c.headerCell().setStringValue(Locale.localizedString("Modified"));
            c.setMinWidth((100f));
            c.setWidth((150));
            c.setMaxWidth((500));
            c.setResizingMask(NSTableColumn.NSTableColumnAutoresizingMask | NSTableColumn.NSTableColumnUserResizingMask);
            c.setDataCell(textCellPrototype);
            table.addTableColumn(c);
        }
        table.removeTableColumn(table.tableColumnWithIdentifier(CDBrowserTableDataSource.OWNER_COLUMN));
        if(Preferences.instance().getBoolean("browser.columnOwner")) {
            NSTableColumn c = browserListColumnsFactory.create(CDBrowserTableDataSource.OWNER_COLUMN);
            c.headerCell().setStringValue(Locale.localizedString("Owner"));
            c.setMinWidth((50));
            c.setWidth((80));
            c.setMaxWidth((500));
            c.setResizingMask(NSTableColumn.NSTableColumnAutoresizingMask | NSTableColumn.NSTableColumnUserResizingMask);
            c.setDataCell(textCellPrototype);
            table.addTableColumn(c);
        }
        table.removeTableColumn(table.tableColumnWithIdentifier(CDBrowserTableDataSource.GROUP_COLUMN));
        if(Preferences.instance().getBoolean("browser.columnGroup")) {
            NSTableColumn c = browserListColumnsFactory.create(CDBrowserTableDataSource.GROUP_COLUMN);
            c.headerCell().setStringValue(Locale.localizedString("Group"));
            c.setMinWidth((50));
            c.setWidth((80));
            c.setMaxWidth((500));
            c.setResizingMask(NSTableColumn.NSTableColumnAutoresizingMask | NSTableColumn.NSTableColumnUserResizingMask);
            c.setDataCell(textCellPrototype);
            table.addTableColumn(c);
        }
        table.removeTableColumn(table.tableColumnWithIdentifier(CDBrowserTableDataSource.PERMISSIONS_COLUMN));
        if(Preferences.instance().getBoolean("browser.columnPermissions")) {
            NSTableColumn c = browserListColumnsFactory.create(CDBrowserTableDataSource.PERMISSIONS_COLUMN);
            c.headerCell().setStringValue(Locale.localizedString("Permissions"));
            c.setMinWidth((100));
            c.setWidth((100));
            c.setMaxWidth((800));
            c.setResizingMask(NSTableColumn.NSTableColumnAutoresizingMask | NSTableColumn.NSTableColumnUserResizingMask);
            c.setDataCell(textCellPrototype);
            table.addTableColumn(c);
        }
        table.removeTableColumn(table.tableColumnWithIdentifier(CDBrowserTableDataSource.KIND_COLUMN));
        if(Preferences.instance().getBoolean("browser.columnKind")) {
            NSTableColumn c = browserListColumnsFactory.create(CDBrowserTableDataSource.KIND_COLUMN);
            c.headerCell().setStringValue(Locale.localizedString("Kind"));
            c.setMinWidth((50));
            c.setWidth((80));
            c.setMaxWidth((500));
            c.setResizingMask(NSTableColumn.NSTableColumnAutoresizingMask | NSTableColumn.NSTableColumnUserResizingMask);
            c.setDataCell(textCellPrototype);
            table.addTableColumn(c);
        }
        table.setIndicatorImage_inTableColumn((browserListViewDelegate).isSortedAscending() ?
                NSImage.imageNamed("NSAscendingSortIndicator") :
                NSImage.imageNamed("NSDescendingSortIndicator"),
                table.tableColumnWithIdentifier(Preferences.instance().getProperty("browser.sort.column")));
        table.setAutosaveTableColumns(true);
        table.sizeToFit();
        this.reloadData(false);
    }

    private CDBookmarkTableDataSource bookmarkModel;

    private NSTableView bookmarkTable;
    private CDAbstractTableDelegate<Host> bookmarkTableDelegate;

    public void setBookmarkTable(NSTableView view) {
        this.bookmarkTable = view;
        this.bookmarkTable.setDataSource((this.bookmarkModel = new CDBookmarkTableDataSource(
                this, HostCollection.defaultCollection())
        ).id());
        this.bookmarkTable.setDelegate((this.bookmarkTableDelegate = new CDAbstractTableDelegate<Host>() {
            public String tooltip(Host bookmark) {
                return bookmark.toURL();
            }

            public void tableRowDoubleClicked(final ID sender) {
                CDBrowserController.this.connectBookmarkButtonClicked(sender);
            }

            public void enterKeyPressed(final ID sender) {
                this.tableRowDoubleClicked(sender);
            }

            public void deleteKeyPressed(final ID sender) {
                if(bookmarkModel.getSource().allowsDelete()) {
                    CDBrowserController.this.deleteBookmarkButtonClicked(sender);
                }
            }

            public void tableColumnClicked(NSTableView view, NSTableColumn tableColumn) {

            }

            public void selectionDidChange(NSNotification notification) {
                addBookmarkButton.setEnabled(bookmarkModel.getSource().allowsAdd());
                final int selected = bookmarkTable.numberOfSelectedRows().intValue();
                editBookmarkButton.setEnabled(bookmarkModel.getSource().allowsEdit() && selected == 1);
                deleteBookmarkButton.setEnabled(bookmarkModel.getSource().allowsDelete() && selected > 0);
            }
        }).id());
        // receive drag events from types
        this.bookmarkTable.registerForDraggedTypes(NSArray.arrayWithObjects(
                NSPasteboard.URLPboardType,
                NSPasteboard.StringPboardType,
                NSPasteboard.FilenamesPboardType, //accept bookmark files dragged from the Finder
                NSPasteboard.FilesPromisePboardType,
                "HostPBoardType" //moving bookmarks
        ));

        {
            NSTableColumn c = bookmarkTableColumnFactory.create(CDBookmarkTableDataSource.ICON_COLUMN);
            c.headerCell().setStringValue("");
            c.setResizingMask(NSTableColumn.NSTableColumnNoResizing);
            c.setDataCell(imageCellPrototype);
            this.bookmarkTable.addTableColumn(c);
        }
        {
            NSTableColumn c = bookmarkTableColumnFactory.create(CDBookmarkTableDataSource.BOOKMARK_COLUMN);
            c.headerCell().setStringValue(Locale.localizedString("Bookmarks"));
            c.setMinWidth((150));
            c.setResizingMask(NSTableColumn.NSTableColumnAutoresizingMask);
            c.setDataCell(CDBookmarkCell.bookmarkCell());
            this.bookmarkTable.addTableColumn(c);
        }
        {
            NSTableColumn c = bookmarkTableColumnFactory.create(CDBookmarkTableDataSource.STATUS_COLUMN);
            c.headerCell().setStringValue("");
            c.setMinWidth((20));
            c.setWidth((20));
            c.setMaxWidth((20));
            c.setResizingMask(NSTableColumn.NSTableColumnAutoresizingMask);
            c.setDataCell(imageCellPrototype);
            c.dataCell().setAlignment(NSText.NSCenterTextAlignment);
            this.bookmarkTable.addTableColumn(c);
        }

        this._updateBookmarkCell();

        // setting appearance attributes
        this.bookmarkTable.setUsesAlternatingRowBackgroundColors(Preferences.instance().getBoolean("browser.alternatingRows"));
        this.bookmarkTable.setGridStyleMask(NSTableView.NSTableViewSolidHorizontalGridLineMask);

        // selection properties
        this.bookmarkTable.setAllowsMultipleSelection(true);
        this.bookmarkTable.setAllowsEmptySelection(true);
        this.bookmarkTable.setAllowsColumnResizing(false);
        this.bookmarkTable.setAllowsColumnSelection(false);
        this.bookmarkTable.setAllowsColumnReordering(false);
        this.bookmarkTable.sizeToFit();

        HistoryCollection.defaultCollection().addListener(historyCollectionListener);
        HostCollection.defaultCollection().addListener(bookmarkCollectionListener);
        Rendezvous.instance().addListener(rendezvousCollectionListener);
    }

    private final CollectionListener<Host> historyCollectionListener = new CollectionListener<Host>() {
        public void collectionItemAdded(Host item) {
            this.reloadBookmarks();
        }

        public void collectionItemRemoved(Host item) {
            this.reloadBookmarks();
        }

        public void collectionItemChanged(Host item) {
            this.reloadBookmarks();
        }

        private void reloadBookmarks() {
            if(bookmarkModel.getSource().equals(HistoryCollection.defaultCollection())) {
                invoke(new WindowMainAction(CDBrowserController.this) {
                    public void run() {
                        bookmarkTable.reloadData();
                        updateStatusLabel();
                    }
                });
            }
        }
    };

    private final CollectionListener<Host> bookmarkCollectionListener = new CollectionListener<Host>() {
        public void collectionItemAdded(Host item) {
            this.reloadBookmarks();
        }

        public void collectionItemRemoved(Host item) {
            this.reloadBookmarks();
        }

        public void collectionItemChanged(Host item) {
            this.reloadBookmarks();
        }

        private void reloadBookmarks() {
            if(bookmarkModel.getSource().equals(HostCollection.defaultCollection())) {
                invoke(new WindowMainAction(CDBrowserController.this) {
                    public void run() {
                        bookmarkTable.reloadData();
                        updateStatusLabel();
                    }
                });
            }
        }
    };

    private final RendezvousListener rendezvousCollectionListener = new RendezvousListener() {
        public void serviceResolved(String servicename, String hostname) {
            this.reloadBookmarks();
        }

        public void serviceLost(String servicename) {
            this.reloadBookmarks();
        }

        private void reloadBookmarks() {
            if(bookmarkModel.getSource().equals(RendezvousCollection.defaultCollection())) {
                invoke(new WindowMainAction(CDBrowserController.this) {
                    public void run() {
                        bookmarkTable.reloadData();
                        updateStatusLabel();
                    }
                });
            }
        }
    };

    @Outlet
    private NSPopUpButton actionPopupButton;

    public void setActionPopupButton(NSPopUpButton actionPopupButton) {
        this.actionPopupButton = actionPopupButton;
        this.actionPopupButton.setPullsDown(true);
        this.actionPopupButton.setAutoenablesItems(true);
        this.actionPopupButton.itemAtIndex(0).setImage(NSImage.imageNamed("gear.tiff"));
    }

    @Outlet
    private NSComboBox quickConnectPopup;

    private CDController quickConnectPopupModel = new QuickConnectModel();

    public void setQuickConnectPopup(NSComboBox quickConnectPopup) {
        this.quickConnectPopup = quickConnectPopup;
        this.quickConnectPopup.setTarget(this.id());
        this.quickConnectPopup.setCompletes(true);
        this.quickConnectPopup.setAction(Foundation.selector("quickConnectSelectionChanged:"));
        this.quickConnectPopup.setUsesDataSource(true);
        this.quickConnectPopup.setDataSource(quickConnectPopupModel.id());
        NSNotificationCenter.defaultCenter().addObserver(this.id(),
                Foundation.selector("quickConnectWillPopUp:"),
                NSComboBox.ComboBoxWillPopUpNotification,
                this.quickConnectPopup);
        this.quickConnectWillPopUp(null);
    }

    private class QuickConnectModel extends CDController implements NSComboBox.DataSource {
        public int numberOfItemsInComboBox(final NSComboBox combo) {
            return HostCollection.defaultCollection().size();
        }

        public NSObject comboBox_objectValueForItemAtIndex(final NSComboBox sender, final int row) {
            if(row < numberOfItemsInComboBox(sender)) {
                return NSString.stringWithString(HostCollection.defaultCollection().get(row).getNickname());
            }
            return null;
        }
    }

    public void quickConnectWillPopUp(NSNotification notification) {
        int size = HostCollection.defaultCollection().size();
        this.quickConnectPopup.setNumberOfVisibleItems(size > 10 ? 10 : size);
    }

    public void quickConnectSelectionChanged(final NSControl sender) {
        if(null == sender) {
            return;
        }
        String input = (sender).stringValue();
        if(StringUtils.isBlank(input)) {
            return;
        }
        input = input.trim();
        // First look for equivalent bookmarks
        for(Host h : (Iterable<Host>) HostCollection.defaultCollection()) {
            if(h.getNickname().equals(input)) {
                this.mount(h);
                return;
            }
        }
        // Try to parse the input as a URL and extract protocol, hostname, username and password if any.
        this.mount(Host.parse(input));
    }

    @Outlet
    private NSTextField searchField;

    public void setSearchField(NSTextField searchField) {
        this.searchField = searchField;
        this.searchField.setEnabled(false);
        NSNotificationCenter.defaultCenter().addObserver(this.id(),
                Foundation.selector("searchFieldTextDidChange:"),
                NSControl.NSControlTextDidChangeNotification,
                this.searchField);
    }

    /**
     * Change focus to filter field
     *
     * @param sender
     */
    public void searchButtonClicked(final ID sender) {
        this.window().makeFirstResponder(searchField);
    }

    public void searchFieldTextDidChange(NSNotification notification) {
        if(this.getSelectedTabView() == TAB_BOOKMARKS) {
            this.setBookmarkFilter(searchField.stringValue());
        }
        else { // TAB_LIST_VIEW || TAB_OUTLINE_VIEW
            this.setPathFilter(searchField.stringValue());
        }
    }

    private void setBookmarkFilter(final String searchString) {
        if(StringUtils.isBlank(searchString)) {
            this.searchField.setStringValue("");
            this.bookmarkModel.setFilter(null);
        }
        else {
            this.bookmarkModel.setFilter(new HostFilter() {
                public boolean accept(Host host) {
                    return host.getNickname().toLowerCase().contains(searchString.toLowerCase())
                            || host.getHostname().toLowerCase().contains(searchString.toLowerCase());
                }
            });
        }
        this.bookmarkTable.reloadData();
    }

    // ----------------------------------------------------------
    // Manage Bookmarks
    // ----------------------------------------------------------

    public void connectBookmarkButtonClicked(final ID sender) {
        if(bookmarkTable.numberOfSelectedRows().intValue() == 1) {
            final Host selected = bookmarkModel.getSource().get(bookmarkTable.selectedRow().intValue());
            this.mount(selected);
        }
    }

    @Outlet
    private NSButton editBookmarkButton;

    public void setEditBookmarkButton(NSButton editBookmarkButton) {
        this.editBookmarkButton = editBookmarkButton;
        this.editBookmarkButton.setEnabled(false);
        this.editBookmarkButton.setTarget(this.id());
        this.editBookmarkButton.setAction(Foundation.selector("editBookmarkButtonClicked:"));
    }

    public void editBookmarkButtonClicked(final ID sender) {
        CDBookmarkController c = CDBookmarkController.Factory.create(
                bookmarkModel.getSource().get(bookmarkTable.selectedRow().intValue())
        );
        c.window().makeKeyAndOrderFront(null);
    }

    @Outlet
    private NSButton addBookmarkButton;

    public void setAddBookmarkButton(NSButton addBookmarkButton) {
        this.addBookmarkButton = addBookmarkButton;
        this.addBookmarkButton.setTarget(this.id());
        this.addBookmarkButton.setAction(Foundation.selector("addBookmarkButtonClicked:"));
    }

    public void addBookmarkButtonClicked(final ID sender) {
        final Host item;
        if(this.isMounted()) {
            Path selected = this.getSelectedPath();
            if(null == selected || !selected.attributes.isDirectory()) {
                selected = this.workdir();
            }
            item = new Host(this.session.getHost().getAsDictionary());
            item.setDefaultPath(selected.getAbsolute());
        }
        else {
            item = new Host(Protocol.forName(Preferences.instance().getProperty("connection.protocol.default")),
                    Preferences.instance().getProperty("connection.hostname.default"),
                    Preferences.instance().getInteger("connection.port.default"));
        }

        this.toggleBookmarks(true);

        bookmarkModel.setFilter(null);
        bookmarkModel.getSource().add(item);
        final int row = bookmarkModel.getSource().lastIndexOf(item);
        final NSInteger index = new NSInteger(row);
        bookmarkTable.selectRowIndexes(NSIndexSet.indexSetWithIndex(index), false);
        bookmarkTable.scrollRowToVisible(index);
        CDBookmarkController c = CDBookmarkController.Factory.create(item);
        c.window().makeKeyAndOrderFront(null);
    }

    @Outlet
    private NSButton deleteBookmarkButton;

    public void setDeleteBookmarkButton(NSButton deleteBookmarkButton) {
        this.deleteBookmarkButton = deleteBookmarkButton;
        this.deleteBookmarkButton.setEnabled(false);
        this.deleteBookmarkButton.setTarget(this.id());
        this.deleteBookmarkButton.setAction(Foundation.selector("deleteBookmarkButtonClicked:"));
    }

    public void deleteBookmarkButtonClicked(final ID sender) {
        final NSIndexSet iterator = bookmarkTable.selectedRowIndexes();
        NSUInteger[] indexes = new NSUInteger[iterator.count().intValue()];
        int i = 0;
        for(NSUInteger index = iterator.firstIndex(); index.longValue() != NSIndexSet.NSNotFound; index = iterator.indexGreaterThanIndex(index)) {
            if(index.intValue() == -1) {
                break;
            }
            indexes[i] = index;
            i++;
        }
        bookmarkTable.deselectAll(null);
        int j = 0;
        for(i = 0; i < indexes.length; i++) {
            int row = indexes[i].intValue() - j;
            final NSInteger index = new NSInteger(row);
            bookmarkTable.selectRowIndexes(NSIndexSet.indexSetWithIndex(index), false);
            bookmarkTable.scrollRowToVisible(index);
            Host host = bookmarkModel.getSource().get(row);
            final NSAlert alert = NSAlert.alert(Locale.localizedString("Delete Bookmark"),
                    Locale.localizedString("Do you want to delete the selected bookmark?")
                            + " (" + host.getNickname() + ")",
                    Locale.localizedString("Delete"),
                    Locale.localizedString("Cancel"),
                    null);
            switch(alert.runModal()) {
                case CDSheetCallback.DEFAULT_OPTION:
                    bookmarkModel.getSource().remove(row);
                    j++;
            }
        }
        bookmarkTable.deselectAll(null);
    }

    // ----------------------------------------------------------
    // Browser navigation
    // ----------------------------------------------------------

    private static final int NAVIGATION_LEFT_SEGMENT_BUTTON = 0;
    private static final int NAVIGATION_RIGHT_SEGMENT_BUTTON = 1;

    private static final int NAVIGATION_UP_SEGMENT_BUTTON = 0;

    private NSSegmentedControl navigationButton;

    private NSMenu backMenu;
    private MenuDelegate backMenuDelegate;
    private NSMenu forwardMenu;
    private MenuDelegate forwardMenuDelegate;

    public void setNavigationButton(NSSegmentedControl navigationButton) {
        this.navigationButton = navigationButton;
        this.navigationButton.setTarget(this.id());
        this.navigationButton.setAction(Foundation.selector("navigationButtonClicked:"));
        this.backMenu = NSMenu.menu();
        this.backMenu.setDelegate((backMenuDelegate = new BackPathHistoryMenuDelegate(this)).id());
        this.navigationButton.setMenu_forSegment(backMenu, NAVIGATION_LEFT_SEGMENT_BUTTON);
        this.forwardMenu = NSMenu.menu();
        this.forwardMenu.setDelegate((forwardMenuDelegate = new ForwardPathHistoryMenuDelegate(this)).id());
        this.navigationButton.setMenu_forSegment(forwardMenu, NAVIGATION_RIGHT_SEGMENT_BUTTON);
    }

    public void navigationButtonClicked(NSSegmentedControl sender) {
        switch(sender.selectedSegment()) {
            case NAVIGATION_LEFT_SEGMENT_BUTTON: {
                this.backButtonClicked(sender);
                break;
            }
            case NAVIGATION_RIGHT_SEGMENT_BUTTON: {
                this.forwardButtonClicked(sender);
                break;
            }
        }
    }

    public void backButtonClicked(final NSSegmentedControl sender) {
        final Path selected = this.getPreviousPath();
        if(selected != null) {
            final Path previous = this.workdir();
            if(previous.getParent().equals(selected)) {
                this.setWorkdir(selected, previous);
            }
            else {
                this.setWorkdir(selected);
            }
        }
    }

    public void forwardButtonClicked(final NSSegmentedControl sender) {
        final Path selected = this.getForwardPath();
        if(selected != null) {
            this.setWorkdir(selected);
        }
    }

    @Outlet
    private NSSegmentedControl upButton;

    public void setUpButton(NSSegmentedControl upButton) {
        this.upButton = upButton;
        this.upButton.setTarget(this.id());
        this.upButton.setAction(Foundation.selector("upButtonClicked:"));
    }

    public void upButtonClicked(final ID sender) {
        final Path previous = this.workdir();
        this.setWorkdir((Path) previous.getParent(), previous);
    }

    private Path workdir;

    @Outlet
    private NSPopUpButton pathPopupButton;

    public void setPathPopup(NSPopUpButton pathPopupButton) {
        this.pathPopupButton = pathPopupButton;
        this.pathPopupButton.setTarget(this.id());
        this.pathPopupButton.setAction(Foundation.selector("pathPopupSelectionChanged:"));
    }

    private void addPathToNavigation(final Path p) {
        pathPopupButton.addItemWithTitle(p.getAbsolute());
        pathPopupButton.lastItem().setRepresentedObject(p.getAbsolute());
        pathPopupButton.lastItem().setImage(CDIconCache.instance().iconForPath(p, 16));
    }

    private void validateNavigationButtons() {
        if(!this.isMounted()) {
            pathPopupButton.removeAllItems();
        }
        else {
            pathPopupButton.removeAllItems();
            this.addPathToNavigation(workdir);
            Path p = workdir;
            while(!p.getParent().equals(p)) {
                this.addPathToNavigation(p);
                p = (Path) p.getParent();
            }
            this.addPathToNavigation(p);
        }

        this.navigationButton.setEnabled(this.isMounted() && this.getBackHistory().size() > 1,
                NAVIGATION_LEFT_SEGMENT_BUTTON);
        this.navigationButton.setEnabled(this.isMounted() && this.getForwardHistory().size() > 0,
                NAVIGATION_RIGHT_SEGMENT_BUTTON);
        this.upButton.setEnabled(this.isMounted() && !this.workdir().isRoot(),
                NAVIGATION_UP_SEGMENT_BUTTON);

        this.pathPopupButton.setEnabled(this.isMounted());
        final boolean enabled = this.isMounted() || this.getSelectedTabView() == TAB_BOOKMARKS;
        this.searchField.setEnabled(enabled);
        if(!enabled) {
            this.searchField.setStringValue("");
        }
    }

    public void pathPopupSelectionChanged(final ID sender) {
        final String selected = pathPopupButton.itemAtIndex(
                pathPopupButton.indexOfSelectedItem()).representedObject();
        final Path previous = this.workdir();
        if(selected != null) {
            final Path path = PathFactory.createPath(session, selected, Path.DIRECTORY_TYPE);
            this.setWorkdir(path);
            if(previous.getParent().equals(path)) {
                this.setWorkdir(path, previous);
            }
            else {
                this.setWorkdir(path);
            }
        }
    }

    @Outlet
    private NSPopUpButton encodingPopup;

    public void setEncodingPopup(NSPopUpButton encodingPopup) {
        this.encodingPopup = encodingPopup;
        this.encodingPopup.setTarget(this.id());
        this.encodingPopup.setAction(Foundation.selector("encodingButtonClicked:"));
        this.encodingPopup.removeAllItems();
        this.encodingPopup.addItemsWithTitles(NSArray.arrayWithObjects(CDMainController.availableCharsets()));
        this.encodingPopup.selectItemWithTitle(Preferences.instance().getProperty("browser.charset.encoding"));
    }

    public void encodingButtonClicked(final NSPopUpButton sender) {
        this.encodingChanged(sender.titleOfSelectedItem());
    }

    public void encodingMenuClicked(final NSMenuItem sender) {
        this.encodingChanged(sender.title());
    }

    public void encodingChanged(final String encoding) {
        if(null == encoding) {
            return;
        }
        this.setEncoding(encoding);
        if(this.isMounted()) {
            if(this.session.getEncoding().equals(encoding)) {
                return;
            }
            this.background(new BrowserBackgroundAction(this) {
                public void run() {
                    unmountImpl();
                }

                public void cleanup() {
                    session.getHost().setEncoding(encoding);
                    reloadButtonClicked(null);
                }

                @Override
                public String getActivity() {
                    return MessageFormat.format(Locale.localizedString("Disconnecting {0}", "Status"),
                            session.getHost().getHostname());
                }
            });
        }
    }

    /**
     * @param encoding
     */
    private void setEncoding(final String encoding) {
        this.encodingPopup.selectItemWithTitle(encoding);
    }

    // ----------------------------------------------------------
    // Drawers
    // ----------------------------------------------------------

    public void toggleLogDrawer(final ID sender) {
        this.logDrawer.toggle(this.id());
    }

    // ----------------------------------------------------------
    // Status
    // ----------------------------------------------------------

    @Outlet
    protected NSProgressIndicator spinner;

    public void setSpinner(NSProgressIndicator spinner) {
        this.spinner = spinner;
        this.spinner.setDisplayedWhenStopped(false);
        this.spinner.setIndeterminate(true);
    }

    public NSProgressIndicator getSpinner() {
        return spinner;
    }

    @Outlet
    private NSTextField statusLabel;

    public void setStatusLabel(NSTextField statusLabel) {
        this.statusLabel = statusLabel;
    }

    public void updateStatusLabel() {
        String label = Locale.localizedString("Disconnected", "Status");
        if(this.getSelectedTabView() == TAB_BOOKMARKS) {
            label = this.bookmarkTable.numberOfRows() + " " + Locale.localizedString("Bookmarks");
        }
        else {
            if(this.isMounted()) {
                if(this.isConnected()) {
                    label = this.getSelectedBrowserView().numberOfRows() + " " + Locale.localizedString("Files");
                }
            }
        }
        this.updateStatusLabel(label);
    }

    public void updateStatusLabel(String label) {
        // Update the status label at the bottom of the browser window
        statusLabel.setAttributedStringValue(NSAttributedString.attributedStringWithAttributes(label, TRUNCATE_MIDDLE_ATTRIBUTES));
    }

    @Outlet
    private NSButton securityLabel;

    public void setSecurityLabel(NSButton securityLabel) {
        this.securityLabel = securityLabel;
        this.securityLabel.setImage(NSImage.imageNamed("unlocked.tiff"));
        this.securityLabel.setEnabled(false);
        this.securityLabel.setTarget(this.id());
        this.securityLabel.setAction(Foundation.selector("securityLabelClicked:"));
    }

    public void securityLabelClicked(final ID sender) {
        if(session instanceof SSLSession) {
            final X509Certificate[] certificates = ((SSLSession) this.session).getTrustManager().getAcceptedIssuers();
            if(0 == certificates.length) {
                log.warn("No accepted certificates found");
                return;
            }
            Keychain.instance().displayCertificates(certificates);
        }
    }

    // ----------------------------------------------------------
    // Selector methods for the toolbar items
    // ----------------------------------------------------------

    public void quicklookButtonClicked(final ID sender) {
        if(QuickLookFactory.instance().isOpen()) {
            QuickLookFactory.instance().close();
        }
        else {
            final AbstractBrowserTableDelegate delegate = this.getSelectedBrowserDelegate();
            delegate.updateQuickLookSelection(this.getSelectedPaths());
        }
    }

    /**
     * Marks all expanded directories as invalid and tells the
     * browser table to reload its data
     *
     * @param sender
     */
    public void reloadButtonClicked(final ID sender) {
        if(this.isMounted()) {
            final Collection<Path> selected = this.getSelectedPaths();
            switch(this.browserSwitchView.selectedSegment()) {
                case SWITCH_LIST_VIEW: {
                    this.workdir().invalidate();
                    break;
                }
                case SWITCH_OUTLINE_VIEW: {
                    this.workdir().invalidate();
                    for(int i = 0; i < browserOutlineView.numberOfRows().intValue(); i++) {
                        final Path item = this.lookup(new CDPathReference(browserOutlineView.itemAtRow(new NSInteger(i))));
                        if(null == item) {
                            continue;
                        }
                        item.invalidate();
                    }
                    break;
                }
            }
            this.reloadData(selected);
        }
    }

    /**
     * Open a new browser with the current selected folder as the working directory
     *
     * @param sender
     */
    public void newBrowserButtonClicked(final ID sender) {
        Path selected = this.getSelectedPath();
        if(null == selected || !selected.attributes.isDirectory()) {
            selected = this.workdir();
        }
        CDBrowserController c = new CDBrowserController();
        c.cascade();
        c.window().makeKeyAndOrderFront(null);
        final Host host = new Host(this.getSession().getHost().<NSDictionary>getAsDictionary());
        host.setDefaultPath(selected.getAbsolute());
        c.mount(host);
    }

    /**
     * @param source      The original file to duplicate
     * @param destination The destination of the duplicated file
     * @param edit        Open the duplicated file in the external editor
     */
    protected void duplicatePath(final Path source, final Path destination, boolean edit) {
        this.duplicatePaths(Collections.singletonMap(source, destination), edit);
    }

    /**
     * @param selected A map with the original files as the key and the destination
     *                 files as the value
     * @param edit     Open the duplicated files in the external editor
     */
    protected void duplicatePaths(final Map<Path, Path> selected, final boolean edit) {
        final Map<Path, Path> normalized = this.checkHierarchy(selected);
        this.checkOverwrite(normalized.values(), new BrowserBackgroundAction(this) {
            public void run() {
                Iterator<Path> sourcesIter = normalized.keySet().iterator();
                Iterator<Path> destinationsIter = normalized.values().iterator();
                while(sourcesIter.hasNext()) {
                    if(this.isCanceled()) {
                        break;
                    }
                    final Path source = sourcesIter.next();
                    final Path destination = destinationsIter.next();
                    source.copy(destination);
                    source.getParent().invalidate();
                    destination.getParent().invalidate();
                    if(!isConnected()) {
                        break;
                    }
                }
            }

            public void cleanup() {
                for(Path duplicate : normalized.values()) {
                    if(edit) {
                        Editor editor = EditorFactory.createEditor(CDBrowserController.this, duplicate);
                        editor.open();
                    }
                    if(duplicate.getName().charAt(0) == '.') {
                        setShowHiddenFiles(true);
                    }
                }
                reloadData(new ArrayList<Path>(normalized.values()));
            }
        });
    }

    /**
     * @param path    The existing file
     * @param renamed The renamed file
     */
    protected void renamePath(final Path path, final Path renamed) {
        this.renamePaths(Collections.singletonMap(path, renamed));
    }

    /**
     * @param selected A map with the original files as the key and the destination
     *                 files as the value
     */
    protected void renamePaths(final Map<Path, Path> selected) {
        final Map<Path, Path> normalized = this.checkHierarchy(selected);
        this.checkMove(normalized.values(), new BrowserBackgroundAction(this) {
            public void run() {
                Iterator<Path> originalIterator = normalized.keySet().iterator();
                Iterator<Path> renamedIterator = normalized.values().iterator();
                while(originalIterator.hasNext()) {
                    if(this.isCanceled()) {
                        break;
                    }
                    final Path original = originalIterator.next();
                    original.getParent().invalidate();
                    final Path renamed = renamedIterator.next();
                    original.rename(renamed);
                    renamed.invalidate();
                    renamed.getParent().invalidate();
                    if(!isConnected()) {
                        break;
                    }
                }
            }

            public void cleanup() {
                reloadData(new ArrayList<Path>(normalized.values()));
            }
        });
    }

    /**
     * Displays a warning dialog about already existing files
     *
     * @param selected The files to check for existance
     */
    private void checkOverwrite(final java.util.Collection<Path> selected, final BackgroundAction action) {
        if(selected.size() > 0) {
            StringBuffer alertText = new StringBuffer(
                    Locale.localizedString("A file with the same name already exists. Do you want to replace the existing file?"));
            int i = 0;
            Iterator<Path> iter = null;
            boolean shouldWarn = false;
            for(iter = selected.iterator(); i < 10 && iter.hasNext();) {
                Path item = iter.next();
                if(item.exists()) {
                    alertText.append("\n" + Character.toString('\u2022') + " " + item.getName());
                    shouldWarn = true;
                }
                i++;
            }
            if(iter.hasNext()) {
                alertText.append("\n" + Character.toString('\u2022') + " ...)");
            }
            if(shouldWarn) {
                NSAlert alert = NSAlert.alert(
                        Locale.localizedString("Overwrite"), //title
                        alertText.toString(),
                        Locale.localizedString("Overwrite"), // defaultbutton
                        Locale.localizedString("Cancel"), //alternative button
                        null //other button
                );
                this.alert(alert, new CDSheetCallback() {
                    public void callback(final int returncode) {
                        if(returncode == DEFAULT_OPTION) {
                            CDBrowserController.this.background(action);
                        }
                    }
                });
            }
            else {
                this.background(action);
            }
        }
    }

    /**
     * Displays a warning dialog about files to be moved
     *
     * @param selected The files to check for existance
     */
    private void checkMove(final java.util.Collection<Path> selected, final BackgroundAction action) {
        if(selected.size() > 0) {
            if(Preferences.instance().getBoolean("browser.confirmMove")) {
                StringBuffer alertText = new StringBuffer(
                        Locale.localizedString("Do you want to move the selected files?"));
                int i = 0;
                Iterator<Path> iter = null;
                for(iter = selected.iterator(); i < 10 && iter.hasNext();) {
                    Path item = iter.next();
                    alertText.append("\n" + Character.toString('\u2022') + " " + item.getName());
                    i++;
                }
                if(iter.hasNext()) {
                    alertText.append("\n" + Character.toString('\u2022') + " ...)");
                }
                final NSAlert alert = NSAlert.alert(
                        Locale.localizedString("Move"), //title
                        alertText.toString(),
                        Locale.localizedString("Move"), // defaultbutton
                        Locale.localizedString("Cancel"), //alternative button
                        null //other button
                );
                this.alert(alert, new CDSheetCallback() {
                    public void callback(final int returncode) {
                        if(returncode == DEFAULT_OPTION) {
                            checkOverwrite(selected, action);
                        }
                    }
                });
            }
            else {
                this.checkOverwrite(selected, action);
            }
        }
    }

    /**
     * Prunes the map of selected files. Files which are a child of an already included directory
     * are removed from the returned map.
     */
    private Map<Path, Path> checkHierarchy(final Map<Path, Path> selected) {
        final Map<Path, Path> normalized = new HashMap<Path, Path>();
        Iterator<Path> sourcesIter = selected.keySet().iterator();
        Iterator<Path> destinationsIter = selected.values().iterator();
        while(sourcesIter.hasNext()) {
            Path f = sourcesIter.next();
            Path r = destinationsIter.next();
            boolean duplicate = false;
            for(Iterator<Path> normalizedIter = normalized.keySet().iterator(); normalizedIter.hasNext();) {
                Path n = normalizedIter.next();
                if(f.isChild(n)) {
                    // The selected file is a child of a directory
                    // already included for deletion
                    duplicate = true;
                    break;
                }
                if(n.isChild(f)) {
                    // Remove the previously added file as it is a child
                    // of the currently evaluated file
                    normalizedIter.remove();
                }
            }
            if(!duplicate) {
                normalized.put(f, r);
            }
        }
        return normalized;
    }

    /**
     * Prunes the list of selected files. Files which are a child of an already included directory
     * are removed from the returned list.
     */
    protected List<Path> checkHierarchy(final List<Path> selected) {
        final List<Path> normalized = new Collection<Path>();
        for(Path f : selected) {
            boolean duplicate = false;
            for(Path n : normalized) {
                if(f.isChild(n)) {
                    // The selected file is a child of a directory
                    // already included for deletion
                    duplicate = true;
                    break;
                }
            }
            if(!duplicate) {
                normalized.add(f);
            }
        }
        return normalized;
    }

    /**
     * Recursively deletes the file
     *
     * @param file
     */
    public void deletePath(final Path file) {
        this.deletePaths(Collections.singletonList(file));
    }

    /**
     * Recursively deletes the files
     *
     * @param selected The files selected in the browser to delete
     */
    public void deletePaths(final List<Path> selected) {
        final List<Path> normalized = this.checkHierarchy(selected);
        if(normalized.isEmpty()) {
            return;
        }
        StringBuffer alertText =
                new StringBuffer(Locale.localizedString("Really delete the following files? This cannot be undone."));
        int i = 0;
        Iterator<Path> iter = null;
        for(iter = normalized.iterator(); i < 10 && iter.hasNext();) {
            alertText.append("\n").append(Character.toString('\u2022')).append(" ").append(iter.next().getName());
            i++;
        }
        if(iter.hasNext()) {
            alertText.append("\n").append(Character.toString('\u2022')).append(" " + "(...)");
        }
        NSAlert alert = NSAlert.alert(Locale.localizedString("Delete"), //title
                alertText.toString(),
                Locale.localizedString("Delete"), // defaultbutton
                Locale.localizedString("Cancel"), //alternative button
                null //other button
        );
        this.alert(alert, new CDSheetCallback() {
            public void callback(final int returncode) {
                if(returncode == DEFAULT_OPTION) {
                    CDBrowserController.this.deletePathsImpl(normalized);
                }
            }
        });
    }

    private void deletePathsImpl(final List<Path> files) {
        this.background(new BrowserBackgroundAction(this) {
            public void run() {
                for(Path file : files) {
                    if(this.isCanceled()) {
                        break;
                    }
                    final Path f = file;
                    f.delete();
                    f.getParent().invalidate();
                    if(!isConnected()) {
                        break;
                    }
                }
            }

            @Override
            public String getActivity() {
                return MessageFormat.format(Locale.localizedString("Deleting {0}", "Status"), "");
            }

            public void cleanup() {
                reloadData(false);
            }
        });
    }

    /**
     * @param selected
     * @return True if the selected path is editable (not a directory and no known binary file)
     */
    private boolean isEditable(final Path selected) {
        if(selected.attributes.isFile()) {
            return !selected.getBinaryFiletypePattern().matcher(selected.getName()).matches();
        }
        return false;
    }

    /**
     * Keep a reference to the sheet to protect it from being deallocated
     */
    private CDSheetController sheet;

    public void gotoButtonClicked(final ID sender) {
        sheet = new CDGotoController(this) {
            @Override
            public void callback(final int returncode) {
                super.callback(returncode);
                sheet = null;
            }
        };
        sheet.beginSheet();
    }

    public void createFileButtonClicked(final ID sender) {
        sheet = new CDCreateFileController(this) {
            @Override
            public void callback(final int returncode) {
                super.callback(returncode);
                sheet = null;
            }
        };
        sheet.beginSheet();
    }

    public void duplicateFileButtonClicked(final ID sender) {
        sheet = new CDDuplicateFileController(this) {
            @Override
            public void callback(final int returncode) {
                super.callback(returncode);
                sheet = null;
            }
        };
        sheet.beginSheet();
    }

    public void createFolderButtonClicked(final ID sender) {
        sheet = new CDFolderController(this) {
            @Override
            public void callback(final int returncode) {
                super.callback(returncode);
                sheet = null;
            }
        };
        sheet.beginSheet();
    }

    public void renameFileButtonClicked(final ID sender) {
        final NSTableView browser = this.getSelectedBrowserView();
        browser.editRow(
                browser.columnWithIdentifier(CDBrowserTableDataSource.FILENAME_COLUMN),
                browser.selectedRow(), true);
    }

    public void sendCustomCommandClicked(final ID sender) {
        sheet = new CDCommandController(this, this.session) {
            @Override
            public void callback(final int returncode) {
                super.callback(returncode);
                sheet = null;
            }
        };
        sheet.beginSheet();
    }

    public void editMenuClicked(final NSMenuItem sender) {
        for(Path selected : this.getSelectedPaths()) {
            String identifier = EditorFactory.getSupportedOdbEditors().get(sender.title());
            if(identifier != null) {
                Editor editor = EditorFactory.createEditor(this, identifier, selected);
                editor.open();
            }
        }
    }

    public void editButtonClicked(final ID sender) {
        for(Path selected : this.getSelectedPaths()) {
            Editor editor = EditorFactory.createEditor(this, selected);
            editor.open();
        }
    }

    public void openBrowserButtonClicked(final ID sender) {
        NSWorkspace.sharedWorkspace().openURL(NSURL.URLWithString(this.getSelectedPathWebUrl()));
    }

    protected String getSelectedPathWebUrl() {
        Path selected;
        if(this.getSelectionCount() == 1) {
            selected = this.getSelectedPath();
        }
        else {
            selected = this.workdir();
        }
        return selected.toHttpURL();
    }

    private CDInfoController inspector;

    public void infoButtonClicked(final ID sender) {
        if(this.getSelectionCount() > 0) {
            final List<Path> selected = this.getSelectedPaths();
            if(Preferences.instance().getBoolean("browser.info.isInspector")) {
                if(null == inspector || null == inspector.window()) {
                    inspector = CDInfoController.Factory.create(CDBrowserController.this, selected);
                }
                else {
                    inspector.setFiles(selected);
                }
                inspector.window().makeKeyAndOrderFront(null);
            }
            else {
                CDInfoController c = CDInfoController.Factory.create(CDBrowserController.this, selected);
                c.window().makeKeyAndOrderFront(null);
            }
        }
    }

    public void deleteFileButtonClicked(final ID sender) {
        this.deletePaths(this.getSelectedPaths());
    }

    private static String lastSelectedDownloadDirectory = null;

    private NSOpenPanel downloadToPanel;

    public void downloadToButtonClicked(final ID sender) {
        downloadToPanel = NSOpenPanel.openPanel();
        downloadToPanel.setCanChooseDirectories(true);
        downloadToPanel.setCanCreateDirectories(true);
        downloadToPanel.setCanChooseFiles(false);
        downloadToPanel.setAllowsMultipleSelection(false);
        downloadToPanel.setPrompt(Locale.localizedString("Download To"));
        downloadToPanel.setTitle(Locale.localizedString("Download To"));
        downloadToPanel.beginSheetForDirectory(
                lastSelectedDownloadDirectory, //trying to be smart
                null, this.window, this.id(),
                Foundation.selector("downloadToPanelDidEnd:returnCode:contextInfo:"),
                null);
    }

    public void downloadToPanelDidEnd_returnCode_contextInfo(NSOpenPanel sheet, int returncode, final ID contextInfo) {
        sheet.close();
        if(returncode == CDSheetCallback.DEFAULT_OPTION) {
            final Session session = getTransferSession();
            final List<Path> roots = new Collection<Path>();
            for(Path selected : getSelectedPaths()) {
                Path path = PathFactory.createPath(session, selected.getAsDictionary());
                path.setLocal(LocalFactory.createLocal(sheet.filename(), path.getLocal().getName()));
                roots.add(path);
            }
            final Transfer q = new DownloadTransfer(roots);
            transfer(q);
        }
        lastSelectedDownloadDirectory = sheet.filename();
        downloadToPanel = null;
    }

    private NSSavePanel downloadAsPanel;

    public void downloadAsButtonClicked(final ID sender) {
        downloadAsPanel = NSSavePanel.savePanel();
        downloadAsPanel.setMessage(Locale.localizedString("Download the selected file to..."));
        downloadAsPanel.setNameFieldLabel(Locale.localizedString("Download As:"));
        downloadAsPanel.setPrompt(Locale.localizedString("Download"));
        downloadAsPanel.setTitle(Locale.localizedString("Download"));
        downloadAsPanel.setCanCreateDirectories(true);
        downloadAsPanel.beginSheetForDirectory(null, this.getSelectedPath().getLocal().getName(), this.window, this.id(),
                Foundation.selector("downloadAsPanelDidEnd:returnCode:contextInfo:"),
                null);
    }

    public void downloadAsPanelDidEnd_returnCode_contextInfo(NSSavePanel sheet, int returncode, final ID contextInfo) {
        sheet.close();
        if(returncode == CDSheetCallback.DEFAULT_OPTION) {
            String filename;
            if((filename = sheet.filename()) != null) {
                final Path selection = PathFactory.createPath(getTransferSession(), this.getSelectedPath().getAsDictionary());
                selection.setLocal(LocalFactory.createLocal(filename));
                final Transfer q = new DownloadTransfer(selection);
                transfer(q);
            }
        }
    }

    private NSOpenPanel syncPanel;

    public void syncButtonClicked(final ID sender) {
        final Path selection;
        if(this.getSelectionCount() == 1 &&
                this.getSelectedPath().attributes.isDirectory()) {
            selection = this.getSelectedPath();
        }
        else {
            selection = this.workdir();
        }
        syncPanel = NSOpenPanel.openPanel();
        syncPanel.setCanChooseDirectories(selection.attributes.isDirectory());
        syncPanel.setCanChooseFiles(selection.attributes.isFile());
        syncPanel.setCanCreateDirectories(true);
        syncPanel.setAllowsMultipleSelection(false);
        syncPanel.setMessage(Locale.localizedString("Synchronize")
                + " " + selection.getName() + " "
                + Locale.localizedString("with"));
        syncPanel.setPrompt(Locale.localizedString("Choose"));
        syncPanel.setTitle(Locale.localizedString("Synchronize"));
        syncPanel.beginSheetForDirectory(null, null, this.window, this.id(),
                Foundation.selector("syncPanelDidEnd:returnCode:contextInfo:"), null //context info
        );
    }

    public void syncPanelDidEnd_returnCode_contextInfo(NSOpenPanel sheet, int returncode, final ID contextInfo) {
        sheet.close();
        if(returncode == CDSheetCallback.DEFAULT_OPTION) {
            if(sheet.filenames().count().intValue() > 0) {
                final Path selection;
                if(this.getSelectionCount() == 1 &&
                        this.getSelectedPath().attributes.isDirectory()) {
                    selection = PathFactory.createPath(getTransferSession(), this.getSelectedPath().getAsDictionary());
                }
                else {
                    selection = PathFactory.createPath(getTransferSession(), this.workdir().getAsDictionary());
                }
                selection.setLocal(LocalFactory.createLocal(sheet.filenames().lastObject().toString()));
                final Transfer q = new SyncTransfer(selection);
                transfer(q, selection);
            }
        }
    }

    public void downloadButtonClicked(final ID sender) {
        final Session session = this.getTransferSession();
        final List<Path> roots = new Collection<Path>();
        for(Path selected : this.getSelectedPaths()) {
            Path path = PathFactory.createPath(session, selected.getAsDictionary());
            path.setLocal(null);
            roots.add(path);
        }
        final Transfer q = new DownloadTransfer(roots);
        this.transfer(q);
    }

    private static String lastSelectedUploadDirectory = null;

    private NSOpenPanel uploadPanel;

    public void uploadButtonClicked(final ID sender) {
        uploadPanel = NSOpenPanel.openPanel();
        uploadPanel.setCanChooseDirectories(true);
        uploadPanel.setCanCreateDirectories(false);
        uploadPanel.setCanChooseFiles(true);
        uploadPanel.setAllowsMultipleSelection(true);
        uploadPanel.setPrompt(Locale.localizedString("Upload"));
        uploadPanel.setTitle(Locale.localizedString("Upload"));
        uploadPanel.beginSheetForDirectory(lastSelectedUploadDirectory, //trying to be smart
                null, this.window,
                this.id(),
                Foundation.selector("uploadPanelDidEnd:returnCode:contextInfo:"),
                null);
    }

    public void uploadPanelDidEnd_returnCode_contextInfo(NSOpenPanel sheet, int returncode, ID contextInfo) {
        sheet.close();
        if(returncode == CDSheetCallback.DEFAULT_OPTION) {
            Path destination = getSelectedPath();
            if(null == destination) {
                destination = workdir();
            }
            else if(!destination.attributes.isDirectory()) {
                destination = (Path) destination.getParent();
            }
            // selected files on the local filesystem
            NSArray selected = sheet.filenames();
            NSEnumerator iterator = selected.objectEnumerator();
            final Session session = getTransferSession();
            final List<Path> roots = new Collection<Path>();
            NSObject next;
            while((next = iterator.nextObject()) != null) {
                roots.add(PathFactory.createPath(session,
                        destination.getAbsolute(),
                        LocalFactory.createLocal(next.toString())));
            }
            final Transfer q = new UploadTransfer(roots);
            transfer(q, destination);
        }
        lastSelectedUploadDirectory = new File(sheet.filename()).getParent();
        uploadPanel = null;
    }

    /**
     * @return The session to be used for file transfers. Null if not mounted
     */
    protected Session getTransferSession() {
        if(!this.isMounted()) {
            return null;
        }
        if(this.session.getMaxConnections() == 1) {
            return this.session;
        }
        final Host h = new Host(this.session.getHost().<NSDictionary>getAsDictionary());
        // Copy credentials of the browser
        h.getCredentials().setPassword(this.session.getHost().getCredentials().getPassword());
        return SessionFactory.createSession(h);
    }

    /**
     * @param transfer
     * @param workdir  Will reload the data for this directory in the browser after the
     *                 transfer completes
     * @see #transfer(Transfer)
     */
    protected void transfer(final Transfer transfer, final Path workdir) {
        final TransferListener l;
        transfer.addListener(l = new TransferAdapter() {
            @Override
            public void transferDidEnd() {
                if(isMounted()) {
                    workdir.invalidate();
                    if(!transfer.isCanceled()) {
                        invoke(new WindowMainAction(CDBrowserController.this) {
                            public void run() {
                                reloadData(true);
                            }

                            @Override
                            public boolean isValid() {
                                return super.isValid() && CDBrowserController.this.isConnected();
                            }
                        });
                    }
                }
            }
        });
        this.addListener(new CDWindowListener() {
            public void windowWillClose() {
                transfer.removeListener(l);
            }
        });
        this.transfer(transfer);
    }

    /**
     * Trasnfers the files either using the queue or using
     * the browser session if #connection.pool.max is 1
     *
     * @param transfer
     * @see CDTransferController
     */
    protected void transfer(final Transfer transfer) {
        this.transfer(transfer, transfer.getSession().getMaxConnections() == 1);
    }

    /**
     * @param transfer
     * @param useBrowserConnection
     */
    protected void transfer(final Transfer transfer, final boolean useBrowserConnection) {
        this.transfer(transfer, useBrowserConnection, CDTransferPrompt.create(this, transfer));
    }

    protected void transfer(final Transfer transfer, final boolean useBrowserConnection, final TransferPrompt prompt) {
        if(useBrowserConnection) {
            final Speedometer meter = new Speedometer(transfer);
            final TransferListener l;
            final long delay = 0;
            final long period = 500; //in milliseconds
            transfer.addListener(l = new TransferAdapter() {
                /**
                 * Timer to update the progress indicator
                 */
                private Timer progressTimer;

                @Override
                public void willTransferPath(Path path) {
                    meter.reset();
                    progressTimer = new Timer();
                    progressTimer.scheduleAtFixedRate(new TimerTask() {
                        public void run() {
                            invoke(new WindowMainAction(CDBrowserController.this) {
                                public void run() {
                                    CDBrowserController.this.updateStatusLabel(meter.getProgress());
                                }
                            });
                        }
                    }, delay, period);
                }

                @Override
                public void didTransferPath(Path path) {
                    progressTimer.cancel();
                    meter.reset();
                }

                @Override
                public void bandwidthChanged(BandwidthThrottle bandwidth) {
                    meter.reset();
                }
            });
            this.addListener(new CDWindowListener() {
                public void windowWillClose() {
                    transfer.removeListener(l);
                }
            });
            this.background(new BrowserBackgroundAction(this) {
                public void run() {
                    TransferOptions options = new TransferOptions();
                    options.closeSession = false;
                    transfer.start(prompt, options);
                }

                @Override
                public void cancel() {
                    transfer.cancel();
                    super.cancel();
                }

                public void cleanup() {
                    updateStatusLabel();
                }

                @Override
                public String getActivity() {
                    return transfer.getName();
                }
            });
        }
        else {
            CDTransferController.instance().startTransfer(transfer);
        }
    }

    public void insideButtonClicked(final ID sender) {
        final Path selected = this.getSelectedPath(); //last row selected
        if(null == selected) {
            return;
        }
        if(selected.attributes.isDirectory()) {
            this.setWorkdir(selected);
        }
        else if(selected.attributes.isFile() || this.getSelectionCount() > 1) {
            if(Preferences.instance().getBoolean("browser.doubleclick.edit")) {
                this.editButtonClicked(null);
            }
            else {
                this.downloadButtonClicked(null);
            }
        }
    }

    public void connectButtonClicked(final ID sender) {
        final CDSheetController controller = CDConnectionController.instance(this);
        this.addListener(new CDWindowListener() {
            public void windowWillClose() {
                controller.invalidate();
            }
        });
        controller.beginSheet();
    }

    public void interruptButtonClicked(final ID sender) {
        // Remove all pending actions
        for(BackgroundAction action : BackgroundActionRegistry.instance().toArray(
                new BackgroundAction[BackgroundActionRegistry.instance().size()])) {
            action.cancel();
        }
        // Interrupt any pending operation by forcefully closing the socket
        this.interrupt();
    }

    public void disconnectButtonClicked(final ID sender) {
        if(this.isActivityRunning()) {
            this.interruptButtonClicked(sender);
        }
        else {
            this.disconnect();
        }
    }

    public void showHiddenFilesClicked(final NSMenuItem sender) {
        if(sender.state() == NSCell.NSOnState) {
            this.setShowHiddenFiles(false);
            sender.setState(NSCell.NSOffState);
        }
        else if(sender.state() == NSCell.NSOffState) {
            this.setShowHiddenFiles(true);
            sender.setState(NSCell.NSOnState);
        }
        if(this.isMounted()) {
            this.reloadData(true);
        }
    }

    /**
     * @return true if a connection is being opened or is already initialized
     */
    public boolean hasSession() {
        return this.session != null;
    }

    /**
     * @return This browser's session or null if not mounted
     */
    public Session getSession() {
        return this.session;
    }

    /**
     * @return true if the remote file system has been mounted
     */
    public boolean isMounted() {
        return this.hasSession() && this.workdir() != null;
    }

    /**
     * @return true if mounted and the connection to the server is alive
     */
    public boolean isConnected() {
        if(this.isMounted()) {
            return this.session.isConnected();
        }
        return false;
    }

    public void cut(final ID sender) {
        for(Path selected : this.getSelectedPaths()) {
            // Writing data for private use when the item gets dragged to the transfer queue.
            PathPasteboard.getPasteboard(this.getSession().getHost()).add(selected.<NSDictionary>getAsDictionary());
        }
        final NSPasteboard generalPasteboard = NSPasteboard.generalPasteboard();
        generalPasteboard.declareTypes(NSArray.arrayWithObject(NSString.stringWithString(NSPasteboard.StringPboardType)), null);
        if(!generalPasteboard.setStringForType(this.getSelectedPath().getAbsolute(), NSPasteboard.StringPboardType)) {
            log.error("Error writing absolute path of selected item to NSPasteboard.StringPboardType.");
        }
    }

    public void paste(final ID sender) {
        final PathPasteboard<NSDictionary> pasteboard = PathPasteboard.getPasteboard(this.getSession().getHost());
        if(pasteboard.isEmpty()) {
            return;
        }
        final Map<Path, Path> files = new HashMap<Path, Path>();
        Path parent = this.workdir();
        if(this.getSelectionCount() == 1) {
            Path selected = this.getSelectedPath();
            if(selected.attributes.isDirectory()) {
                parent = selected;
            }
            else {
                parent = (Path) selected.getParent();
            }
        }
        for(final Path next : pasteboard.getFiles(this.getSession())) {
            Path current = PathFactory.createPath(getSession(),
                    next.getAbsolute(), next.attributes.getType());
            Path renamed = PathFactory.createPath(getSession(),
                    parent.getAbsolute(), current.getName(), next.attributes.getType());
            files.put(current, renamed);
        }
        pasteboard.clear();
        this.renamePaths(files);
    }

    public void pasteFromFinder(final ID sender) {
        NSPasteboard pboard = NSPasteboard.generalPasteboard();
        if(pboard.availableTypeFromArray(NSArray.arrayWithObject(NSPasteboard.FilenamesPboardType)) != null) {
            NSObject o = pboard.propertyListForType(NSPasteboard.FilenamesPboardType);
            if(o != null) {
                final NSArray elements = Rococoa.cast(o, NSArray.class);
                final Path workdir = this.workdir();
                final Session session = this.getTransferSession();
                final List<Path> roots = new Collection<Path>();
                for(int i = 0; i < elements.count().intValue(); i++) {
                    Path p = PathFactory.createPath(session,
                            workdir.getAbsolute(),
                            LocalFactory.createLocal(elements.objectAtIndex(new NSUInteger(i)).toString()));
                    roots.add(p);
                }
                final Transfer q = new UploadTransfer(roots);
                if(q.numberOfRoots() > 0) {
                    this.transfer(q, workdir);
                }
            }
        }
    }

    public void copyURLButtonClicked(final ID sender) {
        final StringBuffer url = new StringBuffer();
        if(this.getSelectionCount() > 0) {
            for(Iterator<Path> iter = this.getSelectedPaths().iterator(); iter.hasNext();) {
                url.append(iter.next().toURL());
                if(iter.hasNext()) {
                    url.append("\n");
                }
            }
        }
        else {
            url.append(this.workdir().toURL());
        }
        NSPasteboard pboard = NSPasteboard.generalPasteboard();
        pboard.declareTypes(NSArray.arrayWithObject(NSString.stringWithString(NSPasteboard.StringPboardType)), null);
        if(!pboard.setStringForType(url.toString(), NSPasteboard.StringPboardType)) {
            log.error("Error writing URL to NSPasteboard.StringPboardType.");
        }
    }

    public void copyWebURLButtonClicked(final ID sender) {
        NSPasteboard pboard = NSPasteboard.generalPasteboard();
        pboard.declareTypes(NSArray.arrayWithObject(NSPasteboard.StringPboardType), null);
        if(!pboard.setString_forType(this.getSelectedPathWebUrl(), NSPasteboard.StringPboardType)) {
            log.error("Error writing URL to NSPasteboard.StringPboardType.");
        }
    }

    public void openTerminalButtonClicked(final ID sender) {
        final boolean identity = this.getSession().getHost().getCredentials().isPublicKeyAuthentication();
        String workdir = null;
        if(this.getSelectionCount() == 1) {
            Path selected = this.getSelectedPath();
            if(selected.attributes.isDirectory()) {
                workdir = selected.getAbsolute();
            }
        }
        if(null == workdir) {
            workdir = this.workdir.getAbsolute();
        }
        final String command
                = "tell application \"Terminal\"\n"
                + "do script \"ssh -t "
                + (identity ? "-i " + this.getSession().getHost().getCredentials().getIdentity().getAbsolute() : "")
                + " "
                + this.getSession().getHost().getCredentials().getUsername()
                + "@"
                + this.getSession().getHost().getHostname()
                + " "
                + "-p " + this.getSession().getHost().getPort()
                + " "
                + "\\\"cd " + workdir + " && exec \\\\$SHELL\\\"\""
                + "\n"
                + "end tell";
        final NSAppleScript as = NSAppleScript.createWithSource(command);
        as.executeAndReturnError(new PointerByReference());
        NSWorkspace.sharedWorkspace().launchApplication(
                NSWorkspace.sharedWorkspace().absolutePathForAppBundleWithIdentifier("com.apple.Terminal")
        );
    }

    /**
     * @param sender
     */
    public void archiveMenuClicked(final NSMenuItem sender) {
        final Archive archive = Archive.forName(sender.representedObject());
        this.archiveClicked(archive);
    }

    /**
     * @param sender
     */
    public void archiveButtonClicked(final NSToolbarItem sender) {
        this.archiveClicked(Archive.TARGZ);
    }

    /**
     * @param archive
     */
    private void archiveClicked(final Archive archive) {
        final Collection<Path> selected = this.getSelectedPaths();
        this.checkOverwrite(Collections.singletonList(archive.getArchive(selected)), new BrowserBackgroundAction(this) {

            public void run() {
                session.archive(archive, selected);
            }

            public void cleanup() {
                // Update Selection
                reloadData(Collections.singletonList(archive.getArchive(selected)));
            }

            @Override
            public String getActivity() {
                return archive.getCompressCommand(selected);
            }
        });
    }

    /**
     * @param sender
     */
    public void unarchiveButtonClicked(final ID sender) {
        final List<Path> expanded = new ArrayList<Path>();
        for(final Path selected : this.getSelectedPaths()) {
            final Archive archive = Archive.forName(selected.getName());
            if(null == archive) {
                continue;
            }
            this.checkOverwrite(archive.getExpanded(Collections.singletonList(selected)), new BrowserBackgroundAction(this) {
                public void run() {
                    session.unarchive(archive, selected);
                }

                public void cleanup() {
                    expanded.addAll(archive.getExpanded(Collections.singletonList(selected)));
                    // Update Selection
                    reloadData(expanded);
                }

                @Override
                public String getActivity() {
                    return archive.getDecompressCommand(selected);
                }
            });
        }
        ;
    }

    /**
     * @return true if there is any network activity running in the background
     */
    public boolean isActivityRunning() {
        final BackgroundAction current = BackgroundActionRegistry.instance().getCurrent();
        if(null == current) {
            return false;
        }
        if(current instanceof BrowserBackgroundAction) {
            return ((BrowserBackgroundAction) current).getController() == this;
        }
        return false;
    }

    /**
     * @param path
     * @return Null if not mounted or lookup fails
     */
    public Path lookup(CDPathReference path) {
        if(this.isMounted()) {
            final Session session = this.getSession();
            final Cache<Path> cache = session.cache();
            return cache.lookup(path);
        }
        return null;
    }

    /**
     * Accessor to the working directory
     *
     * @return The current working directory or null if no file system is mounted
     */
    protected Path workdir() {
        return this.workdir;
    }

    public void setWorkdir(final Path directory) {
        this.setWorkdir(directory, Collections.<Path>emptyList());
    }

    public void setWorkdir(final Path directory, Path selected) {
        this.setWorkdir(directory, Collections.singletonList(selected));
    }

    /**
     * Sets the current working directory. This will udpate the path selection dropdown button
     * and also add this path to the browsing history. If the path cannot be a working directory (e.g. permission
     * issues trying to enter the directory), reloading the browser view is canceled and the working directory
     * not changed.
     *
     * @param directory The new working directory to display or null to detach any working directory from the browser
     */
    public void setWorkdir(final Path directory, final List<Path> selected) {
        log.debug("setWorkdir:" + directory);
        if(null == directory) {
            // Clear the browser view if no working directory is given
            this.workdir = null;
            this.validateNavigationButtons();
            this.reloadData(false);
            return;
        }
        this.background(new BrowserBackgroundAction(this) {
            @Override
            public String getActivity() {
                return MessageFormat.format(Locale.localizedString("Listing directory {0}", "Status"),
                        directory.getName());
            }

            public void run() {
                if(directory.isCached()) {
                    //Reset the readable attribute
                    directory.childs().attributes().setReadable(true);
                }
                // Get the directory listing in the background
                directory.childs();
                if(directory.childs().attributes().isReadable()) {
                    // Update the working directory if listing is successful
                    workdir = directory;
                    // Update the current working directory
                    addPathToHistory(workdir);
                }
            }

            public void cleanup() {
                // Remove any custom file filter
                setPathFilter(null);

                // Change to last selected browser view
                browserSwitchClicked(Preferences.instance().getInteger("browser.view"));

                validateNavigationButtons();

                // Mark the browser data source as dirty
                reloadData(selected);
            }
        });
    }


    /**
     * Keeps a ordered backward history of previously visited paths
     */
    private List<Path> backHistory = new Collection<Path>();

    /**
     * Keeps a ordered forward history of previously visited paths
     */
    private List<Path> forwardHistory = new Collection<Path>();

    /**
     * @param p
     */
    public void addPathToHistory(final Path p) {
        if(backHistory.size() > 0) {
            // Do not add if this was a reload
            if(p.equals(backHistory.get(backHistory.size() - 1))) {
                return;
            }
        }
        backHistory.add(p);
    }

    /**
     * Returns the prevously browsed path and moves it to the forward history
     *
     * @return The previously browsed path or null if there is none
     */
    public Path getPreviousPath() {
        int size = backHistory.size();
        if(size > 1) {
            forwardHistory.add(backHistory.get(size - 1));
            Path p = backHistory.get(size - 2);
            //delete the fetched path - otherwise we produce a loop
            backHistory.remove(size - 1);
            backHistory.remove(size - 2);
            return p;
        }
        else if(1 == size) {
            forwardHistory.add(backHistory.get(size - 1));
            return backHistory.get(size - 1);
        }
        return null;
    }

    /**
     * @return The last path browsed before #getPrevoiusPath was called
     * @see #getPreviousPath()
     */
    public Path getForwardPath() {
        int size = forwardHistory.size();
        if(size > 0) {
            Path p = forwardHistory.get(size - 1);
            forwardHistory.remove(size - 1);
            return p;
        }
        return null;
    }

    /**
     * @return The ordered array of prevoiusly visited directories
     */
    public List<Path> getBackHistory() {
        return backHistory;
    }

    /**
     * Remove all entries from the back path history
     */
    public void clearBackHistory() {
        backHistory.clear();
    }

    /**
     * @return The ordered array of prevoiusly visited directories
     */
    public List<Path> getForwardHistory() {
        return forwardHistory;
    }

    /**
     * Remove all entries from the forward path history
     */
    public void clearForwardHistory() {
        forwardHistory.clear();
    }

    /**
     *
     */
    private ConnectionListener listener = null;

    /**
     * Initializes a session for the passed host. Setting up the listeners and adding any callback
     * controllers needed for login, trust management and hostkey verification.
     *
     * @param host
     * @return A session object bound to this browser controller
     */
    private Session init(final Host host) {
        if(this.hasSession()) {
            this.session.removeConnectionListener(listener);
        }
        this.session = SessionFactory.createSession(host);
        if(this.session instanceof ch.cyberduck.core.sftp.SFTPSession) {
            ((ch.cyberduck.core.sftp.SFTPSession) session).setHostKeyVerificationController(
                    new CDHostKeyController(this));
        }
        this.session.setLoginController(new CDLoginController(this));
        this.setWorkdir((Path) null);
        this.setEncoding(this.session.getEncoding());
        this.session.addProgressListener(new ProgressListener() {
            public void message(final String message) {
                invoke(new WindowMainAction(CDBrowserController.this) {
                    public void run() {
                        updateStatusLabel(message);
                    }
                });
            }
        });
        session.addConnectionListener(listener = new ConnectionAdapter() {
            public void connectionWillOpen() {
                invoke(new WindowMainAction(CDBrowserController.this) {
                    public void run() {
                        bookmarkTable.setNeedsDisplay();
                        window.setTitle(host.getNickname());
                        window.setRepresentedFilename("");
                    }
                });
            }

            public void connectionDidOpen() {
                invoke(new WindowMainAction(CDBrowserController.this) {
                    public void run() {
                        getSelectedBrowserView().setNeedsDisplay();
                        bookmarkTable.setNeedsDisplay();

                        Growl.instance().notify("Connection opened", host.getHostname());

                        final HistoryCollection history = HistoryCollection.defaultCollection();
                        history.add(host);

                        // Set the window title
                        window.setRepresentedFilename(history.getFile(host).getAbsolute());

                        if(Preferences.instance().getBoolean("browser.confirmDisconnect")) {
                            window.setDocumentEdited(true);
                        }
                        securityLabel.setImage(session.isSecure() ? NSImage.imageNamed("locked.tiff")
                                : NSImage.imageNamed("unlocked.tiff"));
                        securityLabel.setEnabled(session instanceof SSLSession);
                    }
                });
            }

            public void connectionDidClose() {
                invoke(new WindowMainAction(CDBrowserController.this) {
                    public void run() {
                        getSelectedBrowserView().setNeedsDisplay();
                        bookmarkTable.setNeedsDisplay();

                        if(!isMounted()) {
                            window.setTitle(NSBundle.mainBundle().infoDictionary().objectForKey("CFBundleName").toString());
                            window.setRepresentedFilename("");
                        }
                        window.setDocumentEdited(false);

                        securityLabel.setImage(NSImage.imageNamed("unlocked.tiff"));
                        securityLabel.setEnabled(false);

                        updateStatusLabel();
                    }
                });
            }
        });
        transcript.clear();
        backHistory.clear();
        forwardHistory.clear();
        session.addTranscriptListener(new TranscriptListener() {
            public void log(final boolean request, final String message) {
                if(logDrawer.state() == NSDrawer.OpenState) {
                    invoke(new WindowMainAction(CDBrowserController.this) {
                        public void run() {
                            transcript.log(request, message);
                        }
                    });
                }
            }
        });
        return session;
    }

    /**
     *
     */
    private Session session;

    /**
     * @param host
     * @return The session to be used for any further operations
     */
    public void mount(final Host host) {
        log.debug("mount:" + host);
        this.unmount(new Runnable() {
            public void run() {
                // The browser has no session, we are allowed to proceed
                // Initialize the browser with the new session attaching all listeners
                final Session session = init(host);

                background(new BrowserBackgroundAction(CDBrowserController.this) {
                    public void run() {
                        // Mount this session
                        workdir = session.mount();
                    }

                    public void cleanup() {
                        // Set the working directory
                        setWorkdir(workdir);
                        if(!session.isConnected()) {
                            // Connection attempt failed
                            log.warn("Mount failed:" + host);
                            unmountImpl();
                        }
                    }

                    @Override
                    public String getActivity() {
                        return MessageFormat.format(Locale.localizedString("Mounting {0}", "Status"),
                                host.getHostname());
                    }
                });
            }
        });
    }

    public boolean unmount() {
        return this.unmount(new Runnable() {
            public void run() {
                ;
            }
        });
    }

    /**
     * @param disconnected Callback after the session has been disconnected
     * @return True if the unmount process has finished, false if the user has to agree first
     *         to close the connection
     */
    public boolean unmount(final Runnable disconnected) {
        return this.unmount(new CDSheetCallback() {
            public void callback(int returncode) {
                if(returncode == DEFAULT_OPTION) {
                    unmountImpl(disconnected);
                }
            }
        }, disconnected);
    }

    /**
     * @param callback
     * @param disconnected
     * @return
     */
    public boolean unmount(final CDSheetCallback callback, final Runnable disconnected) {
        log.debug("unmount");
        if(this.isConnected() || this.isActivityRunning()) {
            if(Preferences.instance().getBoolean("browser.confirmDisconnect")) {
                // Defer the unmount to the callback function
                final NSAlert alert = NSAlert.alert(Locale.localizedString("Disconnect from") + " " + this.session.getHost().getHostname(), //title
                        Locale.localizedString("The connection will be closed."), // message
                        Locale.localizedString("Disconnect"), // defaultbutton
                        Locale.localizedString("Cancel"), // alternate button
                        null //other button
                );
                this.alert(alert, callback);
                // No unmount yet
                return false;
            }
            this.unmountImpl(disconnected);
            // Unmount in progress
            return true;
        }
        disconnected.run();
        // Unmount succeeded
        return true;
    }

    /**
     * @param disconnected
     */
    private void unmountImpl(final Runnable disconnected) {
        if(this.isActivityRunning()) {
            this.interrupt();
        }
        final Session session = this.getSession();
        this.background(new BrowserBackgroundAction(this) {
            public void run() {
                unmountImpl();
            }

            public void cleanup() {
                inspector = null;

                // Clear the cache on the main thread to make sure the browser model is not in an invalid state
                session.cache().clear();
                session.getHost().getCredentials().setPassword(null);

                disconnected.run();
            }

            @Override
            public String getActivity() {
                return MessageFormat.format(Locale.localizedString("Disconnecting {0}", "Status"),
                        session.getHost().getHostname());
            }
        });
    }

    /**
     * Will close the session but still display the current working directory without any confirmation
     * from the user
     */
    private void unmountImpl() {
        // This is not synchronized to the <code>mountingLock</code> intentionally; this allows to unmount
        // sessions not yet connected
        if(this.hasSession()) {
            //Close the connection gracefully
            this.session.close();
        }
    }

    /**
     * Interrupt any operation in progress;
     * just closes the socket without any quit message sent to the server
     */
    private void interrupt() {
        if(this.hasSession()) {
            if(this.isActivityRunning()) {
                final BackgroundAction current = BackgroundActionRegistry.instance().getCurrent();
                if(null != current) {
                    current.cancel();
                }
            }
            this.background(new BrowserBackgroundAction(this) {
                public void run() {
                    if(hasSession()) {
                        // Aggressively close the connection to interrupt the current task
                        session.interrupt();
                    }
                }

                public void cleanup() {
                    ;
                }

                @Override
                public String getActivity() {
                    return MessageFormat.format(Locale.localizedString("Disconnecting {0}", "Status"),
                            session.getHost().getHostname());
                }

                public int retry() {
                    return 0;
                }

                private final Object lock = new Object();

                @Override
                public Object lock() {
                    // No synchronization with other tasks
                    return lock;
                }
            });
        }
    }

    /**
     * Unmount this session
     */
    private void disconnect() {
        this.background(new BrowserBackgroundAction(this) {
            public void run() {
                unmountImpl();
            }

            public void cleanup() {
                if(Preferences.instance().getBoolean("browser.disconnect.showBookmarks")) {
                    CDBrowserController.this.toggleBookmarks(true);
                }
            }

            @Override
            public String getActivity() {
                return MessageFormat.format(Locale.localizedString("Disconnecting {0}", "Status"),
                        session.getHost().getHostname());
            }
        });
    }

    /**
     * @param sender
     */
    public void printDocument(final ID sender) {
        NSPrintInfo print = NSPrintInfo.sharedPrintInfo();
        print.setOrientation(NSPrintInfo.NSPrintingOrientation.NSLandscapeOrientation);
        NSPrintOperation op = NSPrintOperation.printOperationWithView_printInfo(this.getSelectedBrowserView(), print);
        op.runOperationModalForWindow_delegate_didRunSelector_contextInfo(this.window(), this.id(),
                Foundation.selector("printOperationDidRun:success:contextInfo:"), null);
    }

    public void printOperationDidRun_success_contextInfo(NSPrintOperation printOperation, boolean success, ID contextInfo) {
        if(success) {
            log.info("Successfully printed" + contextInfo);
        }
    }

    /**
     * @param app
     * @return NSApplication.TerminateLater if the application should not yet be terminated
     */
    public static int applicationShouldTerminate(final NSApplication app) {
        // Determine if there are any open connections
        for(final CDBrowserController controller : CDMainController.getBrowsers()) {
            if(!controller.unmount(new CDSheetCallback() {
                public void callback(final int returncode) {
                    if(returncode == DEFAULT_OPTION) { //Disconnect
                        controller.window().close();
                        if(NSApplication.NSTerminateNow == CDBrowserController.applicationShouldTerminate(app)) {
                            app.terminate(null);
                        }
                    }
                    if(returncode == OTHER_OPTION) { //Cancel
                        app.replyToApplicationShouldTerminate(false);
                    }
                }
            }, new Runnable() {
                public void run() {
                    ;
                }
            })) {
                return NSApplication.NSTerminateLater;
            }
        }
        return NSApplication.NSTerminateNow;
    }

    @Override
    public boolean windowShouldClose(final NSWindow sender) {
        return this.unmount(new Runnable() {
            public void run() {
                sender.close();
            }
        });
    }

    /**
     * @param item
     * @return true if the menu should be enabled
     */
    public boolean validateMenuItem(NSMenuItem item) {
        final Selector action = item.action();
        if(action.equals(Foundation.selector("pasteFromFinder:"))) {
            boolean valid = false;
            if(this.isMounted()) {
                if(NSPasteboard.generalPasteboard().availableTypeFromArray(NSArray.arrayWithObject(NSPasteboard.FilenamesPboardType)) != null) {
                    NSObject o = NSPasteboard.generalPasteboard().propertyListForType(NSPasteboard.FilenamesPboardType);
                    if(o != null) {
                        final NSArray elements = Rococoa.cast(o, NSArray.class);
                        if(elements.count().intValue() == 1) {
                            item.setTitle(Locale.localizedString("Paste") + " \""
                                    + elements.objectAtIndex(new NSUInteger(0)) + "\"");
                        }
                        else {
                            item.setTitle(Locale.localizedString("Paste from Finder") + " (" +
                                    elements.count().intValue() + " " +
                                    Locale.localizedString("files") + ")");
                        }
                        valid = true;
                    }
                }
            }
            if(!valid) {
                item.setTitle(Locale.localizedString("Paste from Finder"));
            }
        }
        else if(action.equals(Foundation.selector("paste:"))) {
            if(this.isMounted() && !PathPasteboard.getPasteboard(this.getSession().getHost()).isEmpty()) {
                if(PathPasteboard.getPasteboard(this.getSession().getHost()).size() == 1) {
                    item.setTitle(Locale.localizedString("Paste") + " \""
                            + PathPasteboard.getPasteboard(this.getSession().getHost()).getFiles(this.getSession()).get(0).getName() + "\"");
                }
                else {
                    item.setTitle(Locale.localizedString("Paste")
                            + " (" + PathPasteboard.getPasteboard(this.getSession().getHost()).size() + " " +
                            Locale.localizedString("files") + ")");
                }
            }
            else {
                item.setTitle(Locale.localizedString("Paste"));
            }
        }
        else if(action.equals(Foundation.selector("cut:"))) {
            int count = this.getSelectionCount();
            if(this.isMounted() && count > 0) {
                if(count > 1) {
                    item.setTitle(Locale.localizedString("Cut")
                            + " " + this.getSelectionCount() + " " +
                            Locale.localizedString("files"));
                }
                else {
                    item.setTitle(Locale.localizedString("Cut") + " \"" + this.getSelectedPath().getName() + "\"");
                }
            }
            else {
                item.setTitle(Locale.localizedString("Cut"));
            }
        }
        else if(action.equals(Foundation.selector("showHiddenFilesClicked:"))) {
            item.setState(this.getFileFilter() instanceof NullPathFilter ? NSCell.NSOnState : NSCell.NSOffState);
        }
        else if(action.equals(Foundation.selector("encodingMenuClicked:"))) {
            if(this.isMounted()) {
                item.setState(this.session.getEncoding().equalsIgnoreCase(
                        item.title()) ? NSCell.NSOnState : NSCell.NSOffState);
            }
            else {
                item.setState(Preferences.instance().getProperty("browser.charset.encoding").equalsIgnoreCase(
                        item.title()) ? NSCell.NSOnState : NSCell.NSOffState);
            }
        }
        else if(action.equals(Foundation.selector("browserSwitchMenuClicked:"))) {
            if(item.tag() == Preferences.instance().getInteger("browser.view")) {
                item.setState(NSCell.NSOnState);
            }
            else {
                item.setState(NSCell.NSOffState);
            }
        }
        else if(action.equals(Foundation.selector("archiveMenuClicked:"))) {
            final Archive archive = Archive.forName(item.representedObject());
            item.setTitle(archive.getTitle(this.getSelectedPaths()));
        }
        else if(action.equals(Foundation.selector("quicklookButtonClicked:"))) {
            item.setKeyEquivalent(" ");
            item.setKeyEquivalentModifierMask(0);
        }
        return this.validateItem(action);
    }

    /**
     * @param action the method selector
     * @return true if the item by that identifier should be enabled
     */
    private boolean validateItem(final Selector action) {
        if(action.equals(Foundation.selector("cut:"))) {
            return this.isMounted() && this.getSelectionCount() > 0;
        }
        if(action.equals(Foundation.selector("pasteFromFinder:"))) {
            if(this.isMounted()) {
                NSPasteboard pboard = NSPasteboard.generalPasteboard();
                if(pboard.availableTypeFromArray(NSArray.arrayWithObject(NSPasteboard.FilenamesPboardType)) != null) {
                    Object o = pboard.propertyListForType(NSPasteboard.FilenamesPboardType);
                    if(o != null) {
                        return true;
                    }
                }
            }
            return false;
        }
        if(action.equals(Foundation.selector("paste:"))) {
            if(this.isMounted()) {
                return !PathPasteboard.getPasteboard(this.getSession().getHost()).isEmpty();
            }
            return false;
        }
        if(action.equals(Foundation.selector("encodingMenuClicked:"))) {
            return !isActivityRunning();
        }
        if(action.equals(Foundation.selector("connectBookmarkButtonClicked:"))) {
            return bookmarkTable.numberOfSelectedRows().intValue() == 1;
        }
        if(action.equals(Foundation.selector("addBookmarkButtonClicked:"))) {
            return bookmarkModel.getSource().allowsAdd();
        }
        if(action.equals(Foundation.selector("deleteBookmarkButtonClicked:"))) {
            return bookmarkModel.getSource().allowsDelete() && bookmarkTable.selectedRow().intValue() != -1;
        }
        if(action.equals(Foundation.selector("editBookmarkButtonClicked:"))) {
            return bookmarkModel.getSource().allowsEdit() && bookmarkTable.numberOfSelectedRows().intValue() == 1;
        }
        if(action.equals(Foundation.selector("editButtonClicked:"))) {
            if(this.isMounted() && this.getSelectionCount() > 0) {
                String editor = EditorFactory.getSelectedEditor();
                if(null == editor) {
                    return false;
                }
                for(Path selected : this.getSelectedPaths()) {
                    if(!this.isEditable(selected)) {
                        return false;
                    }
                }
                return true;
            }
            return false;
        }
        if(action.equals(Foundation.selector("editMenuClicked:"))) {
            if(this.isMounted() && this.getSelectionCount() > 0) {
                for(Path selected : this.getSelectedPaths()) {
                    if(!this.isEditable(selected)) {
                        return false;
                    }
                }
                return true;
            }
            return false;
        }
        if(action.equals(Foundation.selector("searchButtonClicked:"))) {
            return this.isMounted() || this.getSelectedTabView() == TAB_BOOKMARKS;
        }
        if(action.equals(Foundation.selector("quicklookButtonClicked:"))) {
            return QuickLookFactory.instance().isAvailable() && this.isMounted() && this.getSelectionCount() > 0;
        }
        if(action.equals(Foundation.selector("openBrowserButtonClicked:"))) {
            return this.isMounted();
        }
        if(action.equals(Foundation.selector("sendCustomCommandClicked:"))) {
            return this.isMounted() && this.getSession().isSendCommandSupported();
        }
        if(action.equals(Foundation.selector("gotoButtonClicked:"))) {
            return this.isMounted();
        }
        if(action.equals(Foundation.selector("infoButtonClicked:"))) {
            return this.isMounted() && this.getSelectionCount() > 0;
        }
        if(action.equals(Foundation.selector("createFolderButtonClicked:"))) {
            return this.isMounted() && this.workdir().isMkdirSupported();
        }
        if(action.equals(Foundation.selector("createFileButtonClicked:"))) {
            return this.isMounted();
        }
        if(action.equals(Foundation.selector("duplicateFileButtonClicked:"))) {
            if(this.isMounted() && this.getSelectionCount() == 1) {
                final Path selected = this.getSelectedPath();
                if(null == selected) {
                    return false;
                }
                return selected.attributes.isFile();
            }
            return false;
        }
        if(action.equals(Foundation.selector("renameFileButtonClicked:"))) {
            if(this.isMounted() && this.getSelectionCount() == 1) {
                final Path selected = this.getSelectedPath();
                if(null == selected) {
                    return false;
                }
                return selected.isRenameSupported();
            }
            return false;
        }
        if(action.equals(Foundation.selector("deleteFileButtonClicked:"))) {
            return this.isMounted() && this.getSelectionCount() > 0;
        }
        if(action.equals(Foundation.selector("reloadButtonClicked:"))) {
            return this.isMounted();
        }
        if(action.equals(Foundation.selector("newBrowserButtonClicked:"))) {
            return this.isMounted();
        }
        if(action.equals(Foundation.selector("uploadButtonClicked:"))) {
            return this.isMounted();
        }
        if(action.equals(Foundation.selector("syncButtonClicked:"))) {
            return this.isMounted();
        }
        if(action.equals(Foundation.selector("downloadAsButtonClicked:"))) {
            if(this.isMounted() && this.getSelectionCount() == 1) {
                final Path selected = this.getSelectedPath();
                if(null == selected) {
                    return false;
                }
                return !selected.attributes.isVolume();
            }
            return false;
        }
        if(action.equals(Foundation.selector("downloadToButtonClicked:")) || action.equals(Foundation.selector("downloadButtonClicked:"))) {
            if(this.isMounted() && this.getSelectionCount() > 0) {
                final Path selected = this.getSelectedPath();
                if(null == selected) {
                    return false;
                }
                return !selected.attributes.isVolume();
            }
            return false;
        }
        if(action.equals(Foundation.selector("insideButtonClicked:"))) {
            return this.isMounted() && this.getSelectionCount() > 0;
        }
        if(action.equals(Foundation.selector("upButtonClicked:"))) {
            return this.isMounted() && !this.workdir().isRoot();
        }
        if(action.equals(Foundation.selector("backButtonClicked:"))) {
            return this.isMounted() && this.getBackHistory().size() > 1;
        }
        if(action.equals(Foundation.selector("forwardButtonClicked:"))) {
            return this.isMounted() && this.getForwardHistory().size() > 0;
        }
        if(action.equals(Foundation.selector("copyURLButtonClicked:")) || action.equals(Foundation.selector("copyWebURLButtonClicked:"))) {
            return this.isMounted();
        }
        if(action.equals(Foundation.selector("printDocument:"))) {
            return this.isMounted();
        }
        if(action.equals(Foundation.selector("disconnectButtonClicked:"))) {
            if(!this.isConnected()) {
                return this.isActivityRunning();
            }
            return this.isConnected();
        }
        if(action.equals(Foundation.selector("interruptButtonClicked:"))) {
            return this.isActivityRunning();
        }
        if(action.equals(Foundation.selector("gotofolderButtonClicked:"))) {
            return this.isMounted();
        }
        if(action.equals(Foundation.selector("openTerminalButtonClicked:"))) {
            return this.isMounted() && this.getSession() instanceof SFTPSession;
        }
        if(action.equals(Foundation.selector("archiveButtonClicked:")) || action.equals(Foundation.selector("archiveMenuClicked:"))) {
            if(this.isMounted()) {
                if(!this.getSession().isArchiveSupported()) {
                    return false;
                }
                if(this.getSelectionCount() > 0) {
                    for(Path selected : this.getSelectedPaths()) {
                        if(selected.attributes.isFile() && Archive.isArchive(selected.getName())) {
                            // At least one file selected is already an archive. No distinct action possible
                            return false;
                        }
                    }
                    return true;
                }
            }
            return false;
        }
        if(action.equals(Foundation.selector("unarchiveButtonClicked:"))) {
            if(this.isMounted()) {
                if(!this.getSession().isUnarchiveSupported()) {
                    return false;
                }
                if(this.getSelectionCount() > 0) {
                    for(Path selected : this.getSelectedPaths()) {
                        if(selected.attributes.isDirectory()) {
                            return false;
                        }
                        if(!Archive.isArchive(selected.getName())) {
                            return false;
                        }
                    }
                    return true;
                }
            }
            return false;
        }
        return true; // by default everything is enabled
    }

    private static final String TOOLBAR_NEW_CONNECTION = "New Connection";
    private static final String TOOLBAR_BROWSER_VIEW = "Browser View";
    private static final String TOOLBAR_TRANSFERS = "Transfers";
    private static final String TOOLBAR_QUICK_CONNECT = "Quick Connect";
    //    private static final String TOOLBAR_NAVIGATION = "Location";
    private static final String TOOLBAR_TOOLS = "Tools";
    //    private static final String TOOLBAR_HISTORY = "History";
    private static final String TOOLBAR_REFRESH = "Refresh";
    private static final String TOOLBAR_ENCODING = "Encoding";
    private static final String TOOLBAR_SYNCHRONIZE = "Synchronize";
    private static final String TOOLBAR_DOWNLOAD = "Download";
    private static final String TOOLBAR_UPLOAD = "Upload";
    private static final String TOOLBAR_EDIT = "Edit";
    private static final String TOOLBAR_DELETE = "Delete";
    private static final String TOOLBAR_NEW_FOLDER = "New Folder";
    private static final String TOOLBAR_NEW_BOOKMARK = "New Bookmark";
    private static final String TOOLBAR_GET_INFO = "Get Info";
    private static final String TOOLBAR_WEBVIEW = "Open";
    private static final String TOOLBAR_DISCONNECT = "Disconnect";
    private static final String TOOLBAR_GO_TO_FOLDER = "Go to Folder";
    private static final String TOOLBAR_TERMINAL = "Terminal";
    private static final String TOOLBAR_ARCHIVE = "Archive";
    private static final String TOOLBAR_QUICKLOOK = "Quick Look";

    public boolean validateToolbarItem(final NSToolbarItem item) {
        final String identifier = item.itemIdentifier();
        if(identifier.equals(TOOLBAR_EDIT)) {
            final String selectedEditor = EditorFactory.getSelectedEditor();
            if(null == selectedEditor) {
                item.setImage(NSImage.imageNamed("pencil.tiff"));
            }
            else {
                item.setImage(NSWorkspace.sharedWorkspace().iconForFile(
                        NSWorkspace.sharedWorkspace().absolutePathForAppBundleWithIdentifier(selectedEditor))
                );
            }
        }
        if(identifier.equals(TOOLBAR_DISCONNECT)) {
            if(isActivityRunning()) {
                item.setLabel(Locale.localizedString("Stop"));
                item.setPaletteLabel(Locale.localizedString("Stop"));
                item.setToolTip(Locale.localizedString("Cancel current operation in progress"));
                item.setImage(CDIconCache.instance().iconForName("stop", 32));
            }
            else {
                item.setLabel(Locale.localizedString(TOOLBAR_DISCONNECT));
                item.setPaletteLabel(Locale.localizedString(TOOLBAR_DISCONNECT));
                item.setToolTip(Locale.localizedString("Disconnect from server"));
                item.setImage(NSImage.imageNamed("eject.tiff"));
            }
        }
        if(identifier.equals(TOOLBAR_ARCHIVE)) {
            final Path selected = getSelectedPath();
            if(null != selected) {
                if(Archive.isArchive(selected.getName())) {
                    item.setLabel(Locale.localizedString("Unarchive", "Archive"));
                    item.setPaletteLabel(Locale.localizedString("Unarchive"));
                    item.setAction(Foundation.selector("unarchiveButtonClicked:"));
                }
                else {
                    item.setLabel(Locale.localizedString("Archive", "Archive"));
                    item.setPaletteLabel(Locale.localizedString("Archive"));
                    item.setAction(Foundation.selector("archiveButtonClicked:"));
                }
            }
        }
        if(identifier.equals(TOOLBAR_QUICKLOOK)) {
            // Not called because custom view is set
        }
        return validateItem(item.action());
    }

    /**
     * Keep reference to weak toolbar items
     */
    private Map<String, NSToolbarItem> toolbarItems
            = new HashMap<String, NSToolbarItem>();

    public NSToolbarItem toolbar_itemForItemIdentifier_willBeInsertedIntoToolbar(NSToolbar toolbar, final String itemIdentifier, boolean flag) {
        if(!toolbarItems.containsKey(itemIdentifier)) {
            toolbarItems.put(itemIdentifier, NSToolbarItem.itemWithIdentifier(itemIdentifier));
        }
        final NSToolbarItem item = toolbarItems.get(itemIdentifier);
        if(itemIdentifier.equals(TOOLBAR_BROWSER_VIEW)) {
            item.setLabel(Locale.localizedString("View"));
            item.setPaletteLabel(Locale.localizedString("View"));
            item.setToolTip(Locale.localizedString("Switch Browser View"));
            item.setView(browserSwitchView);
            // Add a menu representation for text mode of toolbar
            NSMenuItem viewMenu = NSMenuItem.itemWithTitle(Locale.localizedString("View"), null, "");
            NSMenu viewSubmenu = NSMenu.menu();
            viewSubmenu.addItemWithTitle_action_keyEquivalent(Locale.localizedString("List"),
                    Foundation.selector("browserSwitchMenuClicked:"), "");
            viewSubmenu.itemWithTitle(Locale.localizedString("List")).setTag(0);
            viewSubmenu.addItemWithTitle_action_keyEquivalent(Locale.localizedString("Outline"),
                    Foundation.selector("browserSwitchMenuClicked:"), "");
            viewSubmenu.itemWithTitle(Locale.localizedString("Outline")).setTag(1);
            viewMenu.setSubmenu(viewSubmenu);
            item.setMenuFormRepresentation(viewMenu);
            item.setMinSize(this.browserSwitchView.frame().size);
            item.setMaxSize(this.browserSwitchView.frame().size);
            return item;
        }
        if(itemIdentifier.equals(TOOLBAR_NEW_CONNECTION)) {
            item.setLabel(Locale.localizedString(TOOLBAR_NEW_CONNECTION));
            item.setPaletteLabel(Locale.localizedString(TOOLBAR_NEW_CONNECTION));
            item.setToolTip(Locale.localizedString("Connect to server"));
            item.setImage(NSImage.imageNamed("connect.tiff"));
            item.setTarget(this.id());
            item.setAction(Foundation.selector("connectButtonClicked:"));
            return item;
        }
        if(itemIdentifier.equals(TOOLBAR_TRANSFERS)) {
            item.setLabel(Locale.localizedString(TOOLBAR_TRANSFERS));
            item.setPaletteLabel(Locale.localizedString(TOOLBAR_TRANSFERS));
            item.setToolTip(Locale.localizedString("Show Transfers window"));
            item.setImage(NSImage.imageNamed("queue.tiff"));
            item.setAction(Foundation.selector("showTransferQueueClicked:"));
            return item;
        }
        if(itemIdentifier.equals(TOOLBAR_TOOLS)) {
            item.setLabel(Locale.localizedString("Action"));
            item.setPaletteLabel(Locale.localizedString("Action"));
            item.setView(this.actionPopupButton);
            // Add a menu representation for text mode of toolbar
            NSMenuItem toolMenu = NSMenuItem.itemWithTitle("", null, "");
            toolMenu.setTitle(Locale.localizedString("Action"));
            NSMenu toolSubmenu = NSMenu.menu();
            for(int i = 1; i < this.actionPopupButton.menu().numberOfItems().intValue(); i++) {
                NSMenuItem template = this.actionPopupButton.menu().itemAtIndex(i);
                toolSubmenu.addItem(NSMenuItem.itemWithTitle(template.title(),
                        template.action(),
                        template.keyEquivalent()));
            }
            toolMenu.setSubmenu(toolSubmenu);
            item.setMenuFormRepresentation(toolMenu);
            item.setMinSize(this.actionPopupButton.frame().size);
            item.setMaxSize(this.actionPopupButton.frame().size);
            return item;
        }
        if(itemIdentifier.equals(TOOLBAR_QUICK_CONNECT)) {
            item.setLabel(Locale.localizedString(TOOLBAR_QUICK_CONNECT));
            item.setPaletteLabel(Locale.localizedString(TOOLBAR_QUICK_CONNECT));
            item.setToolTip(Locale.localizedString("Connect to server"));
            item.setView(quickConnectPopup);
            item.setMinSize(this.quickConnectPopup.frame().size);
            item.setMaxSize(this.quickConnectPopup.frame().size);
            return item;
        }
        if(itemIdentifier.equals(TOOLBAR_ENCODING)) {
            item.setLabel(Locale.localizedString(TOOLBAR_ENCODING));
            item.setPaletteLabel(Locale.localizedString(TOOLBAR_ENCODING));
            item.setToolTip(Locale.localizedString("Character Encoding"));
            item.setView(this.encodingPopup);
            // Add a menu representation for text mode of toolbar
            NSMenuItem encodingMenu = NSMenuItem.itemWithTitle(Locale.localizedString(TOOLBAR_ENCODING),
                    Foundation.selector("encodingMenuClicked:"),
                    "");
            String[] charsets = CDMainController.availableCharsets();
            NSMenu charsetMenu = NSMenu.menu();
            for(String charset : charsets) {
                charsetMenu.addItemWithTitle_action_keyEquivalent(charset, Foundation.selector("encodingMenuClicked:"), "");
            }
            encodingMenu.setSubmenu(charsetMenu);
            item.setMenuFormRepresentation(encodingMenu);
            item.setMinSize(this.encodingPopup.frame().size);
            item.setMaxSize(this.encodingPopup.frame().size);
            return item;
        }
        if(itemIdentifier.equals(TOOLBAR_REFRESH)) {
            item.setLabel(Locale.localizedString(TOOLBAR_REFRESH));
            item.setPaletteLabel(Locale.localizedString(TOOLBAR_REFRESH));
            item.setToolTip(Locale.localizedString("Refresh directory listing"));
            item.setImage(NSImage.imageNamed("reload.tiff"));
            item.setTarget(this.id());
            item.setAction(Foundation.selector("reloadButtonClicked:"));
            return item;
        }
        if(itemIdentifier.equals(TOOLBAR_DOWNLOAD)) {
            item.setLabel(Locale.localizedString(TOOLBAR_DOWNLOAD));
            item.setPaletteLabel(Locale.localizedString(TOOLBAR_DOWNLOAD));
            item.setToolTip(Locale.localizedString("Download file"));
            item.setImage(NSImage.imageNamed("download.tiff"));
            item.setTarget(this.id());
            item.setAction(Foundation.selector("downloadButtonClicked:"));
            return item;
        }
        if(itemIdentifier.equals(TOOLBAR_UPLOAD)) {
            item.setLabel(Locale.localizedString(TOOLBAR_UPLOAD));
            item.setPaletteLabel(Locale.localizedString(TOOLBAR_UPLOAD));
            item.setToolTip(Locale.localizedString("Upload local file to the remote host"));
            item.setImage(NSImage.imageNamed("upload.tiff"));
            item.setTarget(this.id());
            item.setAction(Foundation.selector("uploadButtonClicked:"));
            return item;
        }
        if(itemIdentifier.equals(TOOLBAR_SYNCHRONIZE)) {
            item.setLabel(Locale.localizedString(TOOLBAR_SYNCHRONIZE));
            item.setPaletteLabel(Locale.localizedString(TOOLBAR_SYNCHRONIZE));
            item.setToolTip(Locale.localizedString("Synchronize files"));
            item.setImage(NSImage.imageNamed("sync.tiff"));
            item.setTarget(this.id());
            item.setAction(Foundation.selector("syncButtonClicked:"));
            return item;
        }
        if(itemIdentifier.equals(TOOLBAR_GET_INFO)) {
            item.setLabel(Locale.localizedString(TOOLBAR_GET_INFO));
            item.setPaletteLabel(Locale.localizedString(TOOLBAR_GET_INFO));
            item.setToolTip(Locale.localizedString("Show file attributes"));
            item.setImage(NSImage.imageNamed("info.tiff"));
            item.setTarget(this.id());
            item.setAction(Foundation.selector("infoButtonClicked:"));
            return item;
        }
        if(itemIdentifier.equals(TOOLBAR_WEBVIEW)) {
            item.setLabel(Locale.localizedString(TOOLBAR_WEBVIEW));
            item.setPaletteLabel(Locale.localizedString("Open in Web Browser"));
            item.setToolTip(Locale.localizedString("Open in Web Browser"));
            final String browser = URLSchemeHandlerConfiguration.instance().getDefaultHandlerForURLScheme("http");
            if(null == browser) {
                item.setEnabled(false);
                item.setImage(NSImage.imageNamed("notfound.tiff"));
            }
            else {
                String path = NSWorkspace.sharedWorkspace().absolutePathForAppBundleWithIdentifier(browser);
                if(null == path) {
                    item.setImage(NSImage.imageNamed("notfound.tiff"));
                }
                else {
                    item.setImage(NSWorkspace.sharedWorkspace().iconForFile(path));
                }
            }
            item.setTarget(this.id());
            item.setAction(Foundation.selector("openBrowserButtonClicked:"));
            return item;
        }
        if(itemIdentifier.equals(TOOLBAR_EDIT)) {
            item.setLabel(Locale.localizedString(TOOLBAR_EDIT));
            item.setPaletteLabel(Locale.localizedString(TOOLBAR_EDIT));
            item.setToolTip(Locale.localizedString("Edit file in external editor"));
            item.setImage(NSImage.imageNamed("pencil.tiff"));
            item.setTarget(this.id());
            item.setAction(Foundation.selector("editButtonClicked:"));
            // Add a menu representation for text mode of toolbar
            NSMenuItem toolbarMenu = NSMenuItem.itemWithTitle(Locale.localizedString(TOOLBAR_EDIT),
                    Foundation.selector("editMenuClicked:"),
                    "");
            NSMenu editMenu = NSMenu.menu();
            editMenu.setAutoenablesItems(true);
            editMenu.setDelegate(editMenuDelegate.id());
            toolbarMenu.setSubmenu(editMenu);
            item.setMenuFormRepresentation(toolbarMenu);
            return item;
        }
        if(itemIdentifier.equals(TOOLBAR_DELETE)) {
            item.setLabel(Locale.localizedString(TOOLBAR_DELETE));
            item.setPaletteLabel(Locale.localizedString(TOOLBAR_DELETE));
            item.setToolTip(Locale.localizedString("Delete file"));
            item.setImage(NSImage.imageNamed("delete.tiff"));
            item.setTarget(this.id());
            item.setAction(Foundation.selector("deleteFileButtonClicked:"));
            return item;
        }
        if(itemIdentifier.equals(TOOLBAR_NEW_FOLDER)) {
            item.setLabel(Locale.localizedString(TOOLBAR_NEW_FOLDER));
            item.setPaletteLabel(Locale.localizedString(TOOLBAR_NEW_FOLDER));
            item.setToolTip(Locale.localizedString("Create New Folder"));
            item.setImage(NSImage.imageNamed("newfolder.icns"));
            item.setTarget(this.id());
            item.setAction(Foundation.selector("createFolderButtonClicked:"));
            return item;
        }
        if(itemIdentifier.equals(TOOLBAR_NEW_BOOKMARK)) {
            item.setLabel(Locale.localizedString(TOOLBAR_NEW_BOOKMARK));
            item.setPaletteLabel(Locale.localizedString(TOOLBAR_NEW_BOOKMARK));
            item.setToolTip(Locale.localizedString("New Bookmark"));
            item.setImage(CDIconCache.instance().iconForName("bookmark", 32));
            item.setTarget(this.id());
            item.setAction(Foundation.selector("addBookmarkButtonClicked:"));
            return item;
        }
        if(itemIdentifier.equals(TOOLBAR_DISCONNECT)) {
            item.setLabel(Locale.localizedString(TOOLBAR_DISCONNECT));
            item.setPaletteLabel(Locale.localizedString(TOOLBAR_DISCONNECT));
            item.setToolTip(Locale.localizedString("Disconnect from server"));
            item.setImage(NSImage.imageNamed("eject.tiff"));
            item.setAutovalidates(true);
            item.setTarget(this.id());
            item.setAction(Foundation.selector("disconnectButtonClicked:"));
            return item;
        }
        if(itemIdentifier.equals(TOOLBAR_GO_TO_FOLDER)) {
            item.setLabel(Locale.localizedString(TOOLBAR_GO_TO_FOLDER));
            item.setPaletteLabel(Locale.localizedString(TOOLBAR_GO_TO_FOLDER));
            item.setToolTip(Locale.localizedString("Go to Folder"));
            item.setImage(NSImage.imageNamed("goto.tiff"));
            item.setTarget(this.id());
            item.setAction(Foundation.selector("gotoButtonClicked:"));
            return item;
        }
        if(itemIdentifier.equals(TOOLBAR_TERMINAL)) {
            final String t = NSWorkspace.sharedWorkspace().absolutePathForAppBundleWithIdentifier("com.apple.Terminal");
            item.setLabel(NSFileManager.defaultManager().displayNameAtPath(t));
            item.setPaletteLabel(NSFileManager.defaultManager().displayNameAtPath(t));
            item.setImage(CDIconCache.instance().iconForPath(LocalFactory.createLocal(t), 128));
            item.setTarget(this.id());
            item.setAction(Foundation.selector("openTerminalButtonClicked:"));
            return item;
        }
        if(itemIdentifier.equals(TOOLBAR_ARCHIVE)) {
            final String t = NSWorkspace.sharedWorkspace().absolutePathForAppBundleWithIdentifier("com.apple.archiveutility");
            item.setLabel(Locale.localizedString("Archive", "Archive"));
            item.setPaletteLabel(Locale.localizedString("Archive", "Archive"));
            item.setImage(CDIconCache.instance().iconForPath(LocalFactory.createLocal(t), 128));
            item.setTarget(this.id());
            item.setAction(Foundation.selector("archiveButtonClicked:"));
            return item;
        }
        if(itemIdentifier.equals(TOOLBAR_QUICKLOOK)) {
            item.setLabel(Locale.localizedString(TOOLBAR_QUICKLOOK));
            item.setPaletteLabel(Locale.localizedString(TOOLBAR_QUICKLOOK));
            if(QuickLookFactory.instance().isAvailable()) {
                quicklookButton = NSButton.buttonWithFrame(new NSRect(29, 23));
                quicklookButton.setBezelStyle(NSButtonCell.NSTexturedRoundedBezelStyle);
                quicklookButton.setImage(NSImage.imageNamed("NSQuickLookTemplate"));
                quicklookButton.sizeToFit();
                quicklookButton.setTarget(this.id());
                quicklookButton.setAction(Foundation.selector("quicklookButtonClicked:"));
                item.setView(quicklookButton);
                item.setMinSize(quicklookButton.frame().size);
                item.setMaxSize(quicklookButton.frame().size);
            }
            else {
                item.setEnabled(false);
                item.setImage(NSImage.imageNamed("notfound.tiff"));
            }
            return item;
        }
        // itemIdent refered to a toolbar item that is not provide or supported by us or cocoa.
        // Returning null will inform the toolbar this kind of item is not supported.
        return null;
    }

    @Outlet
    private NSButton quicklookButton;

    /**
     * @param toolbar
     * @return The default configuration of toolbar items
     */
    public NSArray toolbarDefaultItemIdentifiers(NSToolbar toolbar) {
        return NSArray.arrayWithObjects(
                TOOLBAR_NEW_CONNECTION,
                NSToolbarItem.NSToolbarSeparatorItemIdentifier,
                TOOLBAR_QUICK_CONNECT,
                TOOLBAR_TOOLS,
                NSToolbarItem.NSToolbarSeparatorItemIdentifier,
                TOOLBAR_REFRESH,
                TOOLBAR_EDIT,
                NSToolbarItem.NSToolbarFlexibleSpaceItemIdentifier,
                TOOLBAR_DISCONNECT
        );
    }

    /**
     * @param toolbar
     * @return All available toolbar items
     */
    public NSArray toolbarAllowedItemIdentifiers(NSToolbar toolbar) {
        return NSArray.arrayWithObjects(
                TOOLBAR_NEW_CONNECTION,
                TOOLBAR_BROWSER_VIEW,
                TOOLBAR_TRANSFERS,
                TOOLBAR_QUICK_CONNECT,
                TOOLBAR_TOOLS,
                TOOLBAR_REFRESH,
                TOOLBAR_ENCODING,
                TOOLBAR_SYNCHRONIZE,
                TOOLBAR_DOWNLOAD,
                TOOLBAR_UPLOAD,
                TOOLBAR_EDIT,
                TOOLBAR_DELETE,
                TOOLBAR_NEW_FOLDER,
                TOOLBAR_NEW_BOOKMARK,
                TOOLBAR_GET_INFO,
                TOOLBAR_WEBVIEW,
                TOOLBAR_TERMINAL,
                TOOLBAR_ARCHIVE,
                TOOLBAR_QUICKLOOK,
                TOOLBAR_DISCONNECT,
                NSToolbarItem.NSToolbarCustomizeToolbarItemIdentifier,
                NSToolbarItem.NSToolbarSpaceItemIdentifier,
                NSToolbarItem.NSToolbarSeparatorItemIdentifier,
                NSToolbarItem.NSToolbarFlexibleSpaceItemIdentifier
        );
    }

    public NSArray toolbarSelectableItemIdentifiers(NSToolbar toolbar) {
        return NSArray.array();
    }

    /**
     * Overrriden to remove any listeners from the session
     */
    @Override
    protected void invalidate() {
        if(this.hasSession()) {
            this.session.removeConnectionListener(this.listener);
        }
        Rendezvous.instance().removeListener(rendezvousCollectionListener);
        HistoryCollection.defaultCollection().removeListener(historyCollectionListener);
        HostCollection.defaultCollection().removeListener(bookmarkCollectionListener);

        bookmarkTable.setDelegate(null);
        bookmarkTable.setDataSource(null);
        bookmarkModel.invalidate();

        browserListView.setDelegate(null);
        browserListView.setDataSource(null);
        browserListModel.invalidate();

        browserOutlineView.setDelegate(null);
        browserOutlineView.setDataSource(null);
        browserOutlineModel.invalidate();

        toolbar.setDelegate(null);
        toolbarItems.clear();

        browserListColumnsFactory.clear();
        browserOutlineColumnsFactory.clear();
        bookmarkTableColumnFactory.clear();

        super.invalidate();
    }
}