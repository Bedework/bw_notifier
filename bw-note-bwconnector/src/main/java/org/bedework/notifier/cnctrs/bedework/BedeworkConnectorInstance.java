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
package org.bedework.notifier.cnctrs.bedework;

import org.bedework.caldav.util.notifications.NotificationType;
import org.bedework.caldav.util.notifications.parse.Parser;
import org.bedework.notifier.cnctrs.AbstractConnectorInstance;
import org.bedework.notifier.cnctrs.Connector;
import org.bedework.notifier.db.NotifyDb;
import org.bedework.notifier.db.Subscription;
import org.bedework.notifier.exception.NoteException;
import org.bedework.notifier.notifications.Note;
import org.bedework.notifier.notifications.Note.DeliveryMethod;
import org.bedework.util.dav.DavUtil;
import org.bedework.util.dav.DavUtil.DavChild;
import org.bedework.util.dav.DavUtil.DavProp;
import org.bedework.util.http.BasicHttpClient;
import org.bedework.util.misc.Util;
import org.bedework.util.xml.XmlUtil;
import org.bedework.util.xml.tagdefs.AppleServerTags;
import org.bedework.util.xml.tagdefs.WebdavTags;

import org.apache.http.Header;
import org.apache.http.HttpException;
import org.apache.http.message.BasicHeader;
import org.w3c.dom.Element;

import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import javax.servlet.http.HttpServletResponse;
import javax.xml.namespace.QName;

/** Handles bedework synch interactions.
 *
 * @author Mike Douglass
 */
public class BedeworkConnectorInstance extends AbstractConnectorInstance {
  @SuppressWarnings("unused")
  private final BedeworkConnectorConfig config;

  private final BedeworkConnector cnctr;

  private BasicHttpClient client;

  private DavUtil dav;

  private static final Collection<QName> noteTypeProps =
          new ArrayList<>();
  static {
    noteTypeProps.add(AppleServerTags.notificationtype);
  }

  BedeworkConnectorInstance(final BedeworkConnectorConfig config,
                            final BedeworkConnector cnctr,
                            final Subscription sub) {
    super(sub);
    this.config = config;
    this.cnctr = cnctr;
  }

  @Override
  public Connector getConnector() {
    return cnctr;
  }

  @Override
  public boolean changed() throws NoteException {
    /* This implementation needs to at least check the change token for the
     * collection and match it against the stored token.
     */
    return false;
  }

  @Override
  public boolean check(final NotifyDb db) throws NoteException {
    /* Will do a query on the configured resource directory and add
       a list of hrefs for notifications.

       This could be a filtered query to only return the resource types
       we want. For the moment just hope the collection is small.
     */

    final BasicHttpClient cl = getClient();

    try {
      /* The URI stored in the info is located by doing a PROPFIND on
       * the user principal. If we already fetched it we'll use it.
       *
       * TODO We should allow for it changing
       */

      if (sub.getUri() == null) {
        if (!getUri()) {
          return false;
        }
      }

      final String syncToken = getBwsub().getSynchToken();

      final Collection<DavChild> chs = getDav().
              syncReport(cl, sub.getUri(), syncToken, noteTypeProps);

      if (Util.isEmpty(chs)) {
        return false;
      }

      boolean found = false;
      String newSyncToken = null;

      for (final DavChild ch: chs) {
        if ((ch.propVals.size() == 1) &&
                ch.propVals.get(0).name.equals(WebdavTags.syncToken)) {
          newSyncToken = XmlUtil.getElementContent(ch.propVals.get(0).element);
          continue;
        }

        DavProp dp = ch.findProp(AppleServerTags.notificationtype);

        if (dp == null) {
          continue;
        }

        getBwsub().getNoteHrefs().add(ch.uri);
        found = true;
      }

      if (newSyncToken != null) {
        getBwsub().setSynchToken(newSyncToken);
      }
      db.update(sub);

      return found;
    } catch (final NoteException ne ) {
      throw ne;
    } catch (final Throwable t) {
      throw new NoteException(t);
    } finally {
      if (cl != null){
        try {
          cl.release();
        } catch (final HttpException e) {
          error(e);
        }
        cl.close();
      }
    }
  }

  @Override
  public Note nextItem(final NotifyDb db) throws NoteException {
    if (Util.isEmpty(getBwsub().getNoteHrefs())) {
      return null;
    }

    final String noteHref = getBwsub().getNoteHrefs().get(0);

    if (debug) {
      trace("Fetch item " + noteHref);
    }

    final BasicHttpClient cl = getClient();

    try {
      final InputStream is = cl.get(noteHref,
                                    "application/xml",
                                    getAuthHeaders());

      final NotificationType nt = Parser.fromXml(is);

      // TODO use nt.getDtstamp()?

      ItemInfo item = new ItemInfo(noteHref, null);
      Note note = new Note(item, nt);

      // TODO temp - until we set this at the other end.
      note.addDeliveryMethod(DeliveryMethod.email);

      /* TODO At this stage we should move the href to a pending list and a
         later action will remove it from that list.

         At restart we put the pending list back on the unprocessed list.

         For the moment just remove it.
       */

      getBwsub().getNoteHrefs().remove(0);
      db.update(sub);

      return note;
    } catch (final Throwable t) {
      throw new NoteException(t);
    } finally {
      if (cl != null){
        try {
          cl.release();
        } catch (final HttpException e) {
          error(e);
        }
        cl.close();
      }
    }
  }

  @Override
  public boolean completeItem(final NotifyDb db,
                              final Note note) throws NoteException {
    /* Because we do a sync-report on teh collection - we won't see the
     * notification unless it changes.
     *
     * Sharing invites will be removed when processed.
     *
     * Sharing responses could be removed.
     *
     * Change notifications we might want to remove.
     *
     */
    final ItemInfo item = note.getItemInfo();

    final NotificationType notification = note.getNotification();
    final QName noteType = notification.getNotification().getElementName();

    if (noteType.equals(AppleServerTags.resourceChange)) {

      final BasicHttpClient cl = getClient();

      try {
        final int response = cl.delete(item.href,
                                      getAuthHeaders());

        return true;
      } catch (final Throwable t) {
        throw new NoteException(t);
      } finally {
        if (cl != null){
          try {
            cl.release();
          } catch (final HttpException e) {
            error(e);
          }
          cl.close();
        }
      }
    }

    return true;
  }

  /* ====================================================================
   *                   Private methods
   * ==================================================================== */

  private BedeworkSubscription getBwsub() {
    return (BedeworkSubscription)sub;
  }

  private static final Collection<QName> notificationURLProps =
          Arrays.asList(WebdavTags.notificationURL,
                        AppleServerTags.notificationURL);

  private boolean getUri() throws NoteException {
    final BasicHttpClient cl = getClient();

    try {
      //cl.setBaseURI(new URI(config.getSystemUrl()));
      // Make the principal href relative
      final DavChild dc = getDav().getProps(cl,
                                            sub.getPrincipalHref().substring(1),
                                            notificationURLProps);

      DavProp dp = dc.findProp(WebdavTags.notificationURL);

      if ((dp == null) || (dp.status != HttpServletResponse.SC_OK)) {
        dp = dc.findProp(AppleServerTags.notificationURL);
      }

      if ((dp == null) || (dp.status != HttpServletResponse.SC_OK)) {
        if (debug) {
          trace("No notification collection");
        }
        // Could delete but might be dangerous - cnctr.getNotifier().deleteSubscription(sub);
        return false;
      }

      final Element href = XmlUtil.getOnlyElement(dp.element);
      sub.setUri(XmlUtil.getElementContent(href));

/*      try {
        cl.setBaseURI(new URI(sub.getUri()));
        return true;
      } catch (final Throwable ignored) {
        if (debug) {
          trace("Bad uri returned: " + sub.getUri());
        }
        // Could delete but might be dangerous - cnctr.getNotifier().deleteSubscription(sub);
        return false;
      }*/
      return true;
    } catch (final Throwable t) {
      throw new NoteException(t);
    }
  }

  private BasicHttpClient getClient() throws NoteException {
    if (client != null) {
      return client;
    }

    try {
      client = new BasicHttpClient(30 * 1000,
                                   false);  // followRedirects
      client.setBaseURI(new URI(config.getSystemUrl()));
      //if (sub.getUri() != null) {
      //  client.setBaseURI(new URI(sub.getUri()));
      //}

      return client;
    } catch (final Throwable t) {
      throw new NoteException(t);
    }
  }

  List<Header> getAuthHeaders() {
    final String userToken = getBwsub().getUserToken();

    if (userToken == null) {
      return cnctr.getAuthHeaders();
    }

    final List<Header> authheaders = new ArrayList<>(cnctr.getAuthHeaders());
    authheaders.add(new BasicHeader("X-BEDEWORK-NOTEPR", sub.getPrincipalHref()));
    authheaders.add(new BasicHeader("X-BEDEWORK-PT", userToken));

    return authheaders;
  }

  private DavUtil getDav() throws NoteException {
    if (dav != null) {
      return dav;
    }

    try {
      dav = new DavUtil(getAuthHeaders());
      return dav;
    } catch (final Throwable t) {
      throw new NoteException(t);
    }
  }
}
