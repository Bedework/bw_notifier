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

import org.bedework.notifier.BaseSubscriptionInfo;
import org.bedework.notifier.notifications.Notification;
import org.bedework.notifier.NotifyDefs.NotifyKind;
import org.bedework.notifier.NotifyEngine;
import org.bedework.notifier.cnctrs.AbstractConnector;
import org.bedework.notifier.cnctrs.ConnectorInstanceMap;
import org.bedework.notifier.conf.ConnectorConfig;
import org.bedework.notifier.db.Subscription;
import org.bedework.notifier.db.SubscriptionConnectorInfo;
import org.bedework.notifier.exception.NoteException;
import org.bedework.util.dav.DavUtil;
import org.bedework.util.dav.DavUtil.DavChild;
import org.bedework.util.http.BasicHttpClient;
import org.bedework.util.misc.Util;

import org.apache.http.Header;
import org.apache.http.message.BasicHeader;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/** The notification inbound processor connector for connections to bedework.
 *
 * <p>This connector sets up inbound connection instances to provide a
 * stream of notification messages into the notification system</p>
 *
 * <p>The notification directory path points to a special collection
 * which contains links to the calendar homes of accounts for which
 * we want outbound notifications</p>
 *
 * @author Mike Douglass
 */
public class BedeworkConnector
      extends AbstractConnector<BedeworkConnector,
                                BedeworkConnectorInstance,
                                Notification,
                                BedeworkConnectorConfig> {
  private final ConnectorInstanceMap<BedeworkConnectorInstance> cinstMap =
      new ConnectorInstanceMap<>();

  /**
   */
  public BedeworkConnector() {
  }

  @Override
  public void start(final String connectorId,
                    final ConnectorConfig conf,
                    final String callbackUri,
                    final NotifyEngine notifier) throws NoteException {
    super.start(connectorId, conf, callbackUri, notifier);

    try {
      config = (BedeworkConnectorConfig)conf;

      final List<String> notifyUrls;

      notifyUrls = getNotifyUrls(config.getNotificationDirHref());

      if (notifyUrls == null) {
        error(config.getNotificationDirHref() + " not available");
        return;
      }

      if (!Util.isEmpty(notifyUrls)) {
        if (debug) {
          trace("Notification collections available on " +
                        config.getNotificationDirHref());
          for (final String s: notifyUrls) {
            trace("Notify url: " + s);
          }
        }
      }

      /* Create a special subscription for this collection and start
         an instance.
       */

      final Subscription sub = new Subscription(null);

      final SubscriptionConnectorInfo subInfo = new SubscriptionConnectorInfo();
      sub.setSourceConnectorInfo(subInfo);
      subInfo.setConnectorId(connectorId);

      /* Wrap it to manipulate it */
      final BaseSubscriptionInfo bi = new BaseSubscriptionInfo(subInfo);

      bi.setUri(config.getNotificationDirHref());
      bi.setRefreshDelay("60000");

      notifier.add(sub);

      stopped = false;
      running = true;
    } catch (final Throwable t) {
      throw new NoteException(t);
    }
  }

  @Override
  public boolean isManager() {
    return false;
  }

  @Override
  public NotifyKind getKind() {
//    return NotifyKind.notify;
    return NotifyKind.poll;
  }

  @Override
  public boolean isReadOnly() {
    return config.getReadOnly();
  }

  @Override
  public boolean getTrustLastmod() {
    return config.getTrustLastmod();
  }

  @Override
  public BedeworkConnectorInstance getConnectorInstance(final Subscription sub) throws NoteException {
    if (!running) {
      return null;
    }

    BedeworkConnectorInstance inst = cinstMap.find(sub);

    if (inst != null) {
      return inst;
    }

    final BedeworkSubscriptionInfo info;

    info = new BedeworkSubscriptionInfo(sub.getSourceConnectorInfo());

    inst = new BedeworkConnectorInstance(config,
                                         this, sub, info);
    cinstMap.add(sub, inst);

    return inst;
  }

  class BedeworkNotificationBatch extends NotificationBatch<Notification> {
  }

  @Override
  public BedeworkNotificationBatch handleCallback(final HttpServletRequest req,
                                     final HttpServletResponse resp,
                                     final List<String> resourceUri) throws NoteException {
    return null;
  }

  @Override
  public void respondCallback(final HttpServletResponse resp,
                              final NotificationBatch<Notification> notifications)
                                                    throws NoteException {
  }

  @Override
  public void stop() throws NoteException {
    stopped = true;
  }

  /* ====================================================================
   *                         Package methods
   * ==================================================================== */

  private List<Header> authheaders;

  List<Header> getAuthHeaders() {
    if (authheaders != null) {
      return authheaders;
    }

    final String id = config.getId();
    final String token = config.getToken();

    if ((id == null) || (token == null)) {
      return null;
    }

    authheaders = new ArrayList<>(1);
    authheaders.add(new BasicHeader("X-BEDEWORK-NOTE", id + ":" + token));

    return authheaders;
  }

  /* ====================================================================
   *                   Private methods
   * ==================================================================== */

  /** Look for collection urls in the special collection. These will be
   * links to additional notify collections.
   *
   * @param href of our special collection
   * @return children hrefs - empty for none - null for bad href
   * @throws Throwable
   */
  private List<String> getNotifyUrls(final String href) throws Throwable {
    BasicHttpClient cl = null;

    try {
      cl = new BasicHttpClient(30 * 1000,
                               false);  // followRedirects

      final Collection<DavChild> chs = new DavUtil(getAuthHeaders()).
              getChildrenUrls(cl, href, null);

      if (chs == null) {
        return null;
      }

      final List<String> urls = new ArrayList<>(chs.size());

      if (Util.isEmpty(chs)) {
        return urls;
      }

      for (final DavChild ch: chs) {
        if (!ch.isCollection) {
          continue;
        }
        urls.add(ch.uri);
      }

      return urls;
    } finally {
      if (cl != null){
        cl.close();
      }
    }
  }
}