/* ********************************************************************
    Licensed to Jasig under one or more contributor license
    agreements. See the NOTICE file distributed with this work
    for additional information regarding copyright ownership.
    Jasig licenses this file to you under the Apache License,
    Version 2.0 (the "License"); you may not use this file
    except in compliance with the License. You may obtain a
    copy of the License at:

    http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing,
    software distributed under the License is distributed on
    an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
    KIND, either express or implied. See the License for the
    specific language governing permissions and limitations
    under the License.
 */
package org.bedework.notifier.outbound.dummy;

import org.bedework.notifier.exception.NoteException;
import org.bedework.notifier.notifications.Note;
import org.bedework.notifier.outbound.common.AbstractAdaptor;

/** The interface implemented by destination adaptors. A destination
 * may be an email address or sms.
 *
 * @author Mike Douglass
 *
 */
public class DummyAdaptor extends AbstractAdaptor<DummyConf> {

	@Override
	public boolean processSharingInvitation(final Note note) throws NoteException {
		info("Call to processSharingInvitation: " + note);
		return false;
	}

	@Override
	public boolean processSubscribeInvitation(final Note note) throws NoteException {
		info("Call to processSubscribeInvitation: " + note);
		return false;
	}

	@Override
	public boolean processResourceChange(final Note note) throws NoteException {
		info("Call to processResourceChange: " + note);
		return false;
	}
}
