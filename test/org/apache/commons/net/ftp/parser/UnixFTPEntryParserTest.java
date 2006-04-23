package org.apache.commons.net.ftp.parser;

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

import junit.framework.Test;
import junit.framework.TestSuite;
import junit.framework.TestCase;

import ch.cyberduck.core.Host;
import ch.cyberduck.core.Session;
import ch.cyberduck.core.SessionFactory;
import ch.cyberduck.core.PathFactory;
import ch.cyberduck.core.Path;

import org.apache.commons.net.ftp.FTPFileEntryParser;

public class UnixFTPEntryParserTest extends TestCase
{
    public UnixFTPEntryParserTest(String name)
    {
        super(name);
    }

    private FTPFileEntryParser parser;
    private Path parentPath;


    public void setUp() throws Exception
    {
        this.parser = new UnixFTPEntryParser();
        this.parentPath = PathFactory.createPath(SessionFactory.createSession(new Host("localhost")),
                "/");
    }

    public void tearDown() throws Exception
    {
        super.tearDown();
    }

    public void testParseFTPEntry() throws Exception
    {
        Path parsed = parser.parseFTPEntry(parentPath,
                "drw-rw-rw-   1 ftp      ftp             0  Mar 11 20:56 ADMIN_Documentation");
        assertEquals(parsed.attributes.getType(), Path.DIRECTORY_TYPE);
        assertEquals(parsed.attributes.getPermission().getMask(), "rw-rw-rw-");
        assertEquals(parsed.attributes.getOwner(), "ftp");
        assertEquals(parsed.attributes.getGroup(), "ftp");
        assertEquals(parsed.getName(), "ADMIN_Documentation");

//        "drw-rw-rw-   1 ftp      ftp             0  Mar 11 20:56 127.0.0.1x
//        "drw-rw-rw-   1 ftp      ftp             0  Mar 11 20:56 ADMIN_Documentation
//        "drw-rw-rw-   1 ftp      ftp             0  Mar 11 20:56 ADMIN_Interfaces
//        "drw-rw-rw-   1 ftp      ftp             0  Mar 11 20:56 ADMIN_Scripts
//        "drw-rw-rw-   1 ftp      ftp             0  Mar 11 20:56 ADMIN_Web
//        "drw-rw-rw-   1 ftp      ftp             0  Mar 11 20:56 Groups
//        "drw-rw-rw-   1 ftp      ftp             0  Mar 11 20:56 My web page
//        "drw-rw-rw-   1 ftp      ftp             0  Mar 11 20:56 Personal
//        "drw-rw-rw-   1 ftp      ftp             0  Mar 11 20:56 Test
//        "drw-rw-rw-   1 ftp      ftp             0  Mar 11 20:56 TestCollaba
//        "drw-rw-rw-   1 ftp      ftp             0  Mar 11 20:56 TestCSSH
    }

    public static Test suite()
    {
        return new TestSuite(UnixFTPEntryParserTest.class);
    }
}
