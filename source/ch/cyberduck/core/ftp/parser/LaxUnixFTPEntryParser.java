package ch.cyberduck.core.ftp.parser;

import ch.cyberduck.core.Status;

import org.apache.commons.net.ftp.FTPFile;
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

/**
 * @version $Id:$
 */
public class LaxUnixFTPEntryParser extends CommonUnixFTPEntryParser {

    private static final String REGEX =
            "([bcdlfmpSs-])"
                    + "(((r|-)(w|-)([xsStTL-]))((r|-)(w|-)([xsStTL-]))((r|-)(w|-)([xsStTL-])))\\+?\\s+"
                    + "(\\d+)\\s+"
                    + "(\\S+)\\s+"
                    + "(?:(\\S+)\\s+)?"
                    /**
                     * file size
                     */
                    + "(\\d+)"
                    /**
                     * file size maybe given in human readable format eg 15.6k
                     */
                    + "(\\.?\\d?)(\\w?)\\s+"
                    /*
                      numeric or standard format date
                    */
                    + "((?:\\d+[-/]\\d+[-/]\\d+)|(?:\\S+\\s+\\S+))\\s+"
                    /*
                       year (for non-recent standard format)
                       or time (for numeric or recent standard format
                    */
                    + "(\\d+(?::\\d+)?)\\s+"

                    + "(\\S*)(\\s*.*)";

    public LaxUnixFTPEntryParser() {
        super(REGEX);
    }

    public FTPFile parseFTPEntry(String entry) {
        if(matches(entry)) {
            String typeStr = group(1);
            String usr = group(16);
            String grp = group(17);
            String filesize = group(18) + group(19);
            String filesizeIndicator = group(20);
            String datestr = group(21) + " " + group(22);
            String name = group(23);
            String endtoken = group(24);
            if(!filesizeIndicator.equals("")) {
                try {
                    double size = Double.parseDouble(filesize);
                    if(filesizeIndicator.equalsIgnoreCase("K")) {
                        size = size * (long) Status.KILO;
                    } else if(filesizeIndicator.equalsIgnoreCase("M")) {
                        size = size * (long) Status.MEGA;
                    } else if(filesizeIndicator.equalsIgnoreCase("G")) {
                        size = size * (long) Status.GIGA;
                    }
                    return this.parseFTPEntry(typeStr, usr, grp, (long) size, datestr, name, endtoken);
                }
                catch(NumberFormatException e) {
                    return this.parseFTPEntry(typeStr, usr, grp, -1, datestr, name, endtoken);
                }
            }
            return this.parseFTPEntry(typeStr, usr, grp, filesize, datestr, name, endtoken);
        }
        return null;
    }
}
