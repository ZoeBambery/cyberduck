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

import com.apple.cocoa.application.*;
import com.apple.cocoa.foundation.NSAttributedString;
import com.apple.cocoa.foundation.NSRange;

import ch.cyberduck.core.Session;
import ch.cyberduck.core.StringUtils;
import ch.cyberduck.core.TranscriptListener;
import ch.cyberduck.ui.cocoa.threading.WindowMainAction;

import java.io.IOException;

import com.enterprisedt.net.ftp.FTPException;

/**
 * @version $Id$
 */
public class CDCommandController extends CDSheetController implements TranscriptListener {

    private NSTextField inputField; //IBOutlet
    private NSTextView responseField; //IBOUtltet

    public void setInputField(NSTextField inputField) {
        this.inputField = inputField;
    }

    public void setResponseField(NSTextView responseField) {
        this.responseField = responseField;
        this.responseField.setEditable(false);
        this.responseField.setSelectable(true);
        this.responseField.setUsesFontPanel(false);
        this.responseField.setRichText(false);
        this.responseField.layoutManager().setDelegate(this);
    }

    public void layoutManagerDidCompleteLayoutForTextContainer(NSLayoutManager layoutManager,
                                                               NSTextContainer textContainer,
                                                               boolean finished) {
        if(finished && this.responseField.window().isVisible()) {
            this.responseField.scrollRangeToVisible(new NSRange(this.responseField.textStorage().length(), 0));
        }
    }

    private Session session;

    public CDCommandController(final CDWindowController parent, final Session session) {
        super(parent);
        this.session = session;
        this.session.addTranscriptListener(this);
    }

    protected String getBundleName() {
        return "Command";
    }

    public void sendButtonClicked(final NSButton sender) {
        String command = this.inputField.stringValue();
        if(StringUtils.hasText(command)) {
            try {
                session.sendCommand(command);
            }
            catch(FTPException e) {
                ; //ignore
            }
            catch(IOException e) {
                this.closeSheet(sender);
            }
        }
    }

    public void log(final String message) {
        CDMainApplication.invoke(new WindowMainAction(this) {
            public void run() {
                responseField.textStorage().replaceCharactersInRange(new NSRange(responseField.textStorage().length(), 0),
                        new NSAttributedString(message + "\n", FIXED_WITH_FONT_ATTRIBUTES));
            }
        });
    }

    protected boolean validateInput() {
        return true;
    }

    public void callback(final int returncode) {
        ;
    }

    protected void invalidate() {
        session.removeTranscriptListener(this);
        super.invalidate();
    }
}