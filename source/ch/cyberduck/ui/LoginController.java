package ch.cyberduck.ui;

/*
 *  Copyright (c) 2003 David Kocher. All rights reserved.
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

import ch.cyberduck.core.Login;

public interface LoginController {

	/**
	 * Call this to allow the user to reenter the new login credentials.
	 * A concrete sublcass could eg. display a panel.
	 * @return true If we whould try again with new login
	 * @param explanation Any additional information why the login failed.
	 */
	public boolean loginFailure(Login login, String explanation);

}
