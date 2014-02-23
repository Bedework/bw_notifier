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
package org.bedework.notifier.cnctrs.manager;

import org.bedework.notifier.cnctrs.AbstractConnectorInstance;
import org.bedework.notifier.cnctrs.Connector;
import org.bedework.notifier.exception.NoteException;

import org.oasis_open.docs.ws_calendar.ns.soap.BaseResponseType;
import org.oasis_open.docs.ws_calendar.ns.soap.DeleteItemResponseType;
import org.oasis_open.docs.ws_calendar.ns.soap.FetchItemResponseType;
import org.oasis_open.docs.ws_calendar.ns.soap.UpdateItemResponseType;
import org.oasis_open.docs.ws_calendar.ns.soap.UpdateItemType;

import java.util.List;

/** A null connector instance
 *
 * @author Mike Douglass
 */
public class NotifyConnectorInstance extends AbstractConnectorInstance {
  private NotifyConnector cnctr;

  NotifyConnectorInstance(final NotifyConnector cnctr){
    super(null);

    this.cnctr = cnctr;
  }

  @Override
  public Connector getConnector() {
    return cnctr;
  }

  @Override
  public BaseResponseType open() throws NoteException {
    return null;
  }

  @Override
  public boolean changed() throws NoteException {
    return false;
  }

  @Override
  public NotifyItemsInfo getItemsInfo() throws NoteException {
    throw new NoteException("Uncallable");
  }

  @Override
  public DeleteItemResponseType deleteItem(final String href) throws NoteException {
    throw new NoteException("Uncallable");
  }

  @Override
  public FetchItemResponseType fetchItem(final String href) throws NoteException {
    throw new NoteException("Uncallable");
  }

  @Override
  public List<FetchItemResponseType> fetchItems(final List<String> hrefs) throws NoteException {
    return null;
  }

  @Override
  public UpdateItemResponseType updateItem(final UpdateItemType updates) throws NoteException {
    throw new NoteException("Uncallable");
  }
}