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

import org.apache.log4j.Logger;

import ch.cyberduck.core.Queue;

/**
 * @version $Id$
 */
public class CDProgressCell extends CDTableCell {
    private static Logger log = Logger.getLogger(CDProgressCell.class);

    public CDProgressCell() {
        super();
    }

    protected CDProgressCell(NSCoder decoder, long token) {
        super(decoder, token);
    }

    protected void encodeWithCoder(NSCoder encoder) {
        super.encodeWithCoder(encoder);
    }

	private NSView subview;

    public void setObjectValue(Object subview) {
        this.subview = (NSView)subview;
    }
	
    public void drawInteriorWithFrameInView(NSRect cellFrame, NSView controlView) {
        super.drawInteriorWithFrameInView(cellFrame, controlView);
		this.subview.setFrame(cellFrame);
		if (this.subview.superview() != controlView) {
			controlView.addSubview(this.subview);
		}
    }
}