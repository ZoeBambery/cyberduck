package ch.cyberduck.core.ftp.parser;

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

import ch.cyberduck.core.ftp.FTPParserFactory;
import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPFileEntryParser;

import java.util.Calendar;

/**
 * @version $Id: TrellixFTPEntryParserTest.java 2042 2006-04-23 01:39:32Z dkocher $
 */
public class TrellixFTPEntryParserTest extends TestCase {

    public TrellixFTPEntryParserTest(String name) {
        super(name);
    }

    private FTPFileEntryParser parser;


    public void setUp() throws Exception {
        this.parser = new FTPParserFactory().createFileEntryParser("Trellix FTP Server 1.0 (Linux|Unix|Windows)");
    }

    public void tearDown() throws Exception {
        super.tearDown();
    }

    public void testTrellix() throws Exception {
        FTPFile parsed = null;

        //#1213
        parsed = parser.parseFTPEntry(
                "-rw-r--r--  FTP  User       10439 Apr 20 05:29 ASCheckbox_2_0.zip"
        );
        assertNotNull(parsed);
        assertEquals(parsed.getName(), "ASCheckbox_2_0.zip");
        assertTrue(parsed.getType() == FTPFile.FILE_TYPE);
        assertEquals(10439, parsed.getSize());
        assertTrue(parsed.getTimestamp().get(Calendar.MONTH) == Calendar.APRIL);
        assertTrue(parsed.getTimestamp().get(Calendar.DAY_OF_MONTH) == 20);
    }

    public static Test suite() {
        return new TestSuite(UnixFTPEntryParserTest.class);
    }
}