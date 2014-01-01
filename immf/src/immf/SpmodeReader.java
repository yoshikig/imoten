/*
 * imoten - i mode.net mail tensou(forward)
 *
 * Copyright (C) 2013, 2014 ryu aka 508.P905 (http://code.google.com/p/imoten/)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 *
 */

package immf;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Store;

public abstract class SpmodeReader{
	public abstract Store connect(Store str) throws MessagingException;
	public abstract void getMessages() throws MessagingException;
	public abstract int getMessageCount();
	public abstract Message readMessage();
	public abstract void restoreMessage(String id, Message msg);
	public abstract String getLatestId();
	public abstract void updateLastId();
	protected abstract String getLastId();
	public abstract void waitMessage();
	public abstract void close();
}