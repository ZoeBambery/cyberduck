package ch.cyberduck.core;

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

import com.apple.cocoa.foundation.*;

import java.util.List;
import java.util.Iterator;
import java.io.File;

/**
* @version $Id$
 */
public class SyncQueue extends Queue {
	
	public NSMutableDictionary getAsDictionary() {
        NSMutableDictionary dict = super.getAsDictionary();
        dict.setObjectForKey(Queue.KIND_SYNC+"", "Kind");
        return dict;
    }
	
	protected List getChilds(List list, Path p) {
		if(p.getLocal().getTimestamp().before(p.attributes.getTimestamp())) {
			list.add(p);
			if (p.attributes.isDirectory() && !p.attributes.isSymbolicLink()) {
				p.status.setSize(0);
				for (Iterator i = p.list(false, true).iterator(); i.hasNext();) {
					Path child = (Path)i.next();
					child.setLocal(new Local(p.getLocal(), child.getName()));
					this.getChilds(list, child);
				}
			}
		}
		else if(p.getLocal().getTimestamp().after(p.attributes.getTimestamp())) {
			list.add(p);
			if (p.getLocal().isDirectory()) {
				p.attributes.setType(Path.DIRECTORY_TYPE);
				p.status.setSize(0);
				File[] files = p.getLocal().listFiles();
				for (int i = 0; i < files.length; i++) {
					Path child = PathFactory.createPath(p.getSession(), p.getAbsolute(), new Local(files[i].getAbsolutePath()));
					// users complaining about .DS_Store files getting uploaded. It should be apple fixing their crappy file system, but whatever.
					if (!child.getName().equals(".DS_Store")) {
						this.getChilds(list, child);
					}
				}
			}
			else if (p.getLocal().isFile()) {
				p.attributes.setType(Path.FILE_TYPE);
				p.status.setSize(p.getLocal().length()); //setting the file size to the known size of the local file
			}
		}
		else if(p.getLocal().getTimestamp().equals(p.attributes.getTimestamp())) {
			// no sync needed
		}
		return list;
	}
	
	protected void process(Path p) {
		p.sync(true);
	}	
}