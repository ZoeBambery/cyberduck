package ch.cyberduck.ui.cocoa;

/*
 *  Copyright (c) 2004 David Kocher. All rights reserved.
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

import com.apple.cocoa.application.*;
import com.apple.cocoa.foundation.*;

import java.util.List;

import org.apache.log4j.Logger;

import ch.cyberduck.core.Path;
import ch.cyberduck.core.Status;
import ch.cyberduck.core.Validator;

/**
 * @version $Id$
 */
public abstract class CDValidatorController implements Validator {
    protected static Logger log = Logger.getLogger(CDValidatorController.class);

    private static NSMutableArray instances = new NSMutableArray();

    protected CDController windowController;

    public CDValidatorController(CDController windowController) {
        this.windowController = windowController;
        instances.addObject(this);
    }

    private NSImageView iconView; // IBOutlet

    public void setIconView(NSImageView iconView) {
        this.iconView = iconView;
    }

    private NSTextField alertTextField; // IBOutlet

    public void setAlertTextField(NSTextField alertTextField) {
        this.alertTextField = alertTextField;
    }

    private NSButton applyCheckbox; // IBOutlet

    public void setApplyCheckbox(NSButton applyCheckbox) {
        this.applyCheckbox = applyCheckbox;
    }

    private NSButton resumeButton; // IBOutlet

    public void setResumeButton(NSButton resumeButton) {
        this.resumeButton = resumeButton;
    }

    private NSWindow window; // IBOutlet

    public void setWindow(NSWindow window) {
        this.window = window;
        this.window.setDelegate(this);
    }

    public NSWindow window() {
        return this.window;
    }

    public void windowWillClose(NSNotification notification) {
        instances.removeObject(this);
    }

	protected Validator validator;

	/**
		* The user canceled this request, no further validation should be taken
     */
    private boolean isCanceled = false;
	
	public boolean isCanceled() {
		return this.isCanceled;
	}
	
	public void setCanceled(boolean c) {
		this.isCanceled = c;
	}
	
    /*
     * Use the same settings for all succeeding items to check
     */
    private boolean applySettingsToAll = false;
    /**
     * Include this file in the transfer queue
     */
    private boolean include = false;
    /**
     * The resume button has been selected
     */
    private boolean resumeChoosen = false;

	public boolean validate(Path p) {
        if (!this.isCanceled()) {
			if(this.validator.validate(p)) {
				return true;
			}
			return this.prompt(p);
        }
        log.info("Canceled " + p.getName() + " - no further validation needed");
        return false;
	}
		
    public boolean prompt(final Path path) {
        while (windowController.window().attachedSheet() != null) {
            try {
                log.debug("Sleeping...");
                Thread.sleep(1000); //milliseconds
            }
            catch (InterruptedException e) {
                log.error(e.getMessage());
            }
        }
        if (!applySettingsToAll) {
            synchronized (this) {
                resumeButton.setEnabled(path.status.getCurrent() < path.status.getSize());
                Path remote = path;
                List list = path.getParent().list(); //List cache = path.getParent().cache();
                if (list.indexOf(path) != -1) {
                    remote = (Path)list.get(list.indexOf(path));
                }
                String alertText =
                        NSBundle.localizedString("Local", "") + ":\n"
                        + "\t" + path.getLocal().getAbsolute() + "\n"
                        + "\t" + NSBundle.localizedString("Size", "") + ": " + Status.getSizeAsString(path.getLocal().length()) + "\n"
                        + "\t" + NSBundle.localizedString("Modified", "") + ": " + path.getLocal().getTimestampAsString() + "\n"
                        + NSBundle.localizedString("Remote", "") + ":\n"
                        + "\t" + remote.getAbsolute() + "\n"
                        + "\t" + NSBundle.localizedString("Size", "") + ": " + Status.getSizeAsString(remote.status.getSize()) + "\n"
                        + "\t" + NSBundle.localizedString("Modified", "") + ": " + remote.attributes.getTimestampAsString() + "\n"
                        ;
                alertTextField.setStringValue(alertText); // message
                NSImage img = NSWorkspace.sharedWorkspace().iconForFileType(path.getExtension());
                img.setScalesWhenResized(true);
                img.setSize(new NSSize(64f, 64f));
                iconView.setImage(img);
                NSApplication.sharedApplication().beginSheet(this.window(), //sheet
                        windowController.window(),
                        CDValidatorController.this, //modalDelegate
                        new NSSelector("validateSheetDidEnd",
                                new Class[]{NSWindow.class, int.class, Object.class}), // did end selector
                        path); //contextInfo
				windowController.window().makeKeyAndOrderFront(null);
                // Waiting for user to make choice
                while (windowController.window().attachedSheet() != null) {
                    try {
                        log.debug("Sleeping...");
                        Thread.sleep(1000); //milliseconds
                    }
                    catch (InterruptedException e) {
                        log.error(e.getMessage());
                    }
                }
            }
        }
        path.status.setResume(resumeChoosen);
        log.info("File " + path.getName() + " will be included:" + include);
        return include;
    }

    public void resumeActionFired(NSButton sender) {
        log.debug("resumeActionFired");
        this.resumeChoosen = true;
        this.include = true;
        NSApplication.sharedApplication().endSheet(this.window(), sender.tag());
    }

    public void overwriteActionFired(NSButton sender) {
        log.debug("overwriteActionFired");
        this.resumeChoosen = false;
        this.include = true;
        NSApplication.sharedApplication().endSheet(this.window(), sender.tag());
    }

    public void skipActionFired(NSButton sender) {
        log.debug("skipActionFired");
        this.resumeChoosen = true;
        this.include = false;
        NSApplication.sharedApplication().endSheet(this.window(), sender.tag());
    }

    public void cancelActionFired(NSButton sender) {
        log.debug("cancelActionFired");
        this.setCanceled(true);
        this.include = false;
        this.resumeChoosen = true;
        NSApplication.sharedApplication().endSheet(this.window(), sender.tag());
    }

    public void validateSheetDidEnd(NSWindow sheet, int returncode, Object contextInfo) {
        sheet.close();
        this.applySettingsToAll = (applyCheckbox.state() == NSCell.OnState);
        log.info("Action will applied to all subsequent validated items");
    }
}