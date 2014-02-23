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
package org.bedework.notifier;

import org.bedework.notifier.cnctrs.Connector;
import org.bedework.notifier.cnctrs.Connector.NotificationBatch;
import org.bedework.notifier.cnctrs.ConnectorInstance;
import org.bedework.notifier.conf.ConnectorConfig;
import org.bedework.notifier.conf.NotifyConfig;
import org.bedework.notifier.db.NotifyDb;
import org.bedework.notifier.db.Subscription;
import org.bedework.notifier.exception.NoteException;
import org.bedework.notifier.service.NoteConnConf;
import org.bedework.util.calendar.XcalUtil.TzGetter;
import org.bedework.util.http.BasicHttpClient;
import org.bedework.util.jmx.ConfigHolder;
import org.bedework.util.misc.Util;
import org.bedework.util.security.PwEncryptionIntf;
import org.bedework.util.timezones.Timezones;
import org.bedework.util.timezones.TimezonesImpl;

import net.fortuna.ical4j.model.TimeZone;
import org.apache.log4j.Logger;
import org.oasis_open.docs.ws_calendar.ns.soap.StatusType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/** Notification processor.
 * <p>The notification processor manages the notifcatiuon service.
 *
 * <p>There are two ends to a subscription handled by connectors.
 *
 * <p>blah blah<</p>
 *
 * @author Mike Douglass
 */
public class NotifyEngine extends TzGetter {
  protected transient Logger log;

  private final boolean debug;

  //private static String appname = "Synch";
  static ConfigHolder<NotifyConfig> cfgHolder;

  private transient PwEncryptionIntf pwEncrypt;

  /* Map of currently active notification subscriptions. These are subscriptions
   * for which we get change messages from the remote system(s).
   */
  private final Map<String, Subscription> activeSubs =
      new HashMap<String, Subscription>();

  private boolean starting;

  private boolean running;

  private boolean stopping;

  //private Configurator config;

  private static Object getNotifierLock = new Object();

  private static NotifyEngine notifier;

  private Timezones timezones;

  static TzGetter tzgetter;

  private NotelingPool notelingPool;

  private NotifyTimer notifyTimer;

  private BlockingQueue<Notification> notificationInQueue;

  /* Where we keep subscriptions that come in while we are starting */
  private List<Subscription> subsList;

  private NotifyDb db;

  private Map<String, Connector> connectorMap = new HashMap<>();

  /* Some counts */

  private StatLong notificationsCt = new StatLong("notifications");

  private StatLong notificationsAddWt = new StatLong("notifications add wait");

  /** This process handles startup notifications and (un)subscriptions.
   *
   */
  private class NotificationInThread extends Thread {
    long lastTrace;

    /**
     */
    public NotificationInThread() {
      super("NotifyIn");
    }

    @Override
    public void run() {
      while (true) {
        if (debug) {
          trace("About to wait for notification");
        }

        try {
          Notification note = notificationInQueue.take();
          if (note == null) {
            continue;
          }

          if (debug) {
            trace("Received notification");
          }

          /*
          if ((note.getSub() != null) && note.getSub().getDeleted()) {
            // Drop it

            if (debug) {
              trace("Dropping deleted notification");
            }

            continue;
          }*/

          notificationsCt.inc();
          Noteling sl = null;

          try {
            /* Get a synchling from the pool */
            while (true) {
              if (stopping) {
                return;
              }

              sl = notelingPool.getNoException();
              if (sl != null) {
                break;
              }
            }

            /* The synchling needs to be running it's own thread. */
            StatusType st = handleNotification(sl, note);

            if (st == StatusType.WARNING) {
              /* Back on the queue - these need to be flagged so we don't get an
               * endless loop - perhaps we need a delay queue
               */

              notificationInQueue.put(note);
            }
          } finally {
            notelingPool.add(sl);
          }

          /* If this is a poll kind then we should add it to a poll queue
           */
          // XXX Add it to poll queue
        } catch (InterruptedException ie) {
          warn("Notification handler shutting down");
          break;
        } catch (Throwable t) {
          if (debug) {
            error(t);
          } else {
            // Try not to flood the log with error traces
            long now = System.currentTimeMillis();
            if ((now - lastTrace) > (30 * 1000)) {
              error(t);
              lastTrace = now;
            } else {
              error(t.getMessage());
            }
          }
        }
      }
    }
  }

  private static NotificationInThread notifyInHandler;

  /** Constructor
   *
   */
  private NotifyEngine() throws NoteException {
    debug = getLogger().isDebugEnabled();

    System.setProperty("com.sun.xml.ws.transport.http.client.HttpTransportPipe.dump",
                       String.valueOf(debug));
  }

  /**
   * @return the syncher
   * @throws org.bedework.notifier.exception.NoteException
   */
  public static NotifyEngine getNotifier() throws NoteException {
    if (notifier != null) {
      return notifier;
    }

    synchronized (getNotifierLock) {
      if (notifier != null) {
        return notifier;
      }
      notifier = new NotifyEngine();
      return notifier;
    }
  }

  /**
   * @param val
   */
  public static void setConfigHolder(final ConfigHolder<NotifyConfig> val) {
    cfgHolder = val;
  }

  /**
   * @return current state of the configuration
   */
  public static NotifyConfig getConfig() {
    if (cfgHolder == null) {
      return null;
    }

    return cfgHolder.getConfig();
  }

  /**
   * @throws org.bedework.notifier.exception.NoteException
   */
  public void updateConfig() throws NoteException {
    if (cfgHolder != null) {
      cfgHolder.putConfig();
    }
  }

  /** Get a timezone object given the id. This will return transient objects
   * registered in the timezone directory
   *
   * @param id
   * @return TimeZone with id or null
   * @throws Throwable
   */
   @Override
  public TimeZone getTz(final String id) throws Throwable {
     return getNotifier().timezones.getTimeZone(id);
   }

   /**
    * @return a getter for timezones
    */
   public static TzGetter getTzGetter() {
     return tzgetter;
   }

  /** Start synch process.
   *
   * @throws org.bedework.notifier.exception.NoteException
   */
  public void start() throws NoteException {
    try {
      if (starting || running) {
        warn("Start called when already starting or running");
        return;
      }

      synchronized (this) {
        subsList = null;

        starting = true;
      }

      db = new NotifyDb(getConfig());

      timezones = new TimezonesImpl();
      timezones.init(getConfig().getTimezonesURI());

      tzgetter = this;

      //DavClient.setDefaultMaxPerHost(20);
      BasicHttpClient.setDefaultMaxPerRoute(20);

      notelingPool = new NotelingPool();
      notelingPool.start(this,
                          getConfig().getNotelingPoolSize(),
                          getConfig().getNotelingPoolTimeout());

      notificationInQueue = new ArrayBlockingQueue<Notification>(100);

      info("**************************************************");
      info("Starting synch");
      info("      callback URI: " + getConfig().getCallbackURI());
      info("**************************************************");

      if (getConfig().getKeystore() != null) {
        System.setProperty("javax.net.ssl.trustStore", getConfig().getKeystore());
        System.setProperty("javax.net.ssl.trustStorePassword", "bedework");
      }

      List<NoteConnConf> connectorConfs = getConfig().getConnectorConfs();
      String callbackUriBase = getConfig().getCallbackURI();

      /* Register the connectors and start them */
      for (NoteConnConf scc: connectorConfs) {
        ConnectorConfig conf = (ConnectorConfig)scc.getConfig();
        String cnctrId = conf.getName();
        info("Register and start connector " + cnctrId);

        registerConnector(cnctrId, conf);

        Connector conn = getConnector(cnctrId);
        scc.setConnector(conn);

        conn.start(cnctrId,
                   conf,
                   callbackUriBase + cnctrId + "/",
                   this);

        while (!conn.isStarted()) {
          /* Wait for it to start */
          synchronized (this) {
            this.wait(250);
          }

          if (conn.isFailed()) {
            error("Connector " + cnctrId + " failed to start");
            break;
          }
        }
      }

      notifyTimer = new NotifyTimer(this);

      /* Get the list of subscriptions from our database and process them.
       * While starting, new subscribe requests get added to the list.
       */

      notifyInHandler = new NotificationInThread();
      notifyInHandler.start();

      try {
        db.open();
        List<Subscription> startList = db.getAll();
        db.close();

        startup:
        while (starting) {
          if (debug) {
            trace("startList has " + startList.size() + " subscriptions");
          }

          for (Subscription sub: startList) {
            setConnectors(sub);

            reschedule(sub);
          }

          synchronized (this) {
            if (subsList == null) {
              // Nothing came in as we started
              starting = false;
              if (stopping) {
                break startup;
              }
              running = true;
              break;
            }

            startList = subsList;
            subsList = null;
          }
        }
      } finally {
        if ((db != null) && db.isOpen()) {
          db.close();
        }
      }

      info("**************************************************");
      info("Synch started");
      info("**************************************************");
    } catch (NoteException se) {
      error(se);
      starting = false;
      running = false;
      throw se;
    } catch (Throwable t) {
      error(t);
      starting = false;
      running = false;
      throw new NoteException(t);
    }
  }

  /** Reschedule a subscription for updates.
   *
   * @param sub
   * @throws org.bedework.notifier.exception.NoteException
   */
  public void reschedule(final Subscription sub) throws NoteException {
    if (debug) {
      trace("reschedule subscription " + sub);
    }

    if (sub.polling()) {
      notifyTimer.schedule(sub, sub.nextRefresh());
      return;
    }

    // XXX start up the add to active subs

    activeSubs.put(sub.getSubscriptionId(), sub);
  }

  /**
   * @return true if we're running
   */
  public boolean getRunning() {
    return running;
  }

  /**
   * @return stats for synch service bean
   */
  public List<Stat> getStats() {
    List<Stat> stats = new ArrayList<Stat>();

    stats.addAll(notelingPool.getStats());
    stats.addAll(notifyTimer.getStats());
    stats.add(notificationsCt);
    stats.add(notificationsAddWt);

    return stats;
  }

  /** Stop synch process.
   *
   */
  public void stop() {
    if (stopping) {
      return;
    }

    stopping = true;

    /* Call stop on each connector
     */
    for (Connector conn: getConnectors()) {
      info("Stopping connector " + conn.getId());
      try {
        conn.stop();
      } catch (Throwable t) {
        if (debug) {
          error(t);
        } else {
          error(t.getMessage());
        }
      }
    }

    info("Connectors stopped");

    if (notelingPool != null) {
      notelingPool.stop();
    }

    notifier = null;

    info("**************************************************");
    info("Notifier shutdown complete");
    info("**************************************************");
  }

  /**
   * @param note
   * @throws org.bedework.notifier.exception.NoteException
   */
  public void handleNotification(final Notification note) throws NoteException {
    try {
      while (true) {
        if (stopping) {
          return;
        }

        if (notificationInQueue.offer(note, 5, TimeUnit.SECONDS)) {
          break;
        }
      }
    } catch (InterruptedException ie) {
    }
  }

  /**
   * @param val
   * @return decrypted string
   * @throws org.bedework.notifier.exception.NoteException
   */
  public String decrypt(final String val) throws NoteException {
    try {
      return getEncrypter().decrypt(val);
    } catch (NoteException se) {
      throw se;
    } catch (Throwable t) {
      throw new NoteException(t);
    }
  }

  /**
   * @return en/decryptor
   * @throws org.bedework.notifier.exception.NoteException
   */
  public PwEncryptionIntf getEncrypter() throws NoteException {
    if (pwEncrypt != null) {
      return pwEncrypt;
    }

    try {
      String pwEncryptClass = "org.bedework.util.security.PwEncryptionDefault";
      //String pwEncryptClass = getSysparsHandler().get().getPwEncryptClass();

      pwEncrypt = (PwEncryptionIntf)Util.getObject(pwEncryptClass,
                                                   PwEncryptionIntf.class);

      pwEncrypt.init(getConfig().getPrivKeys(),
                     getConfig().getPubKeys());

      return pwEncrypt;
    } catch (NoteException se) {
      throw se;
    } catch (Throwable t) {
      t.printStackTrace();
      throw new NoteException(t);
    }
  }

  /** Gets an instance and implants it in the subscription object.
   * @param sub
   * @return ConnectorInstance or throws Exception
   * @throws org.bedework.notifier.exception.NoteException
   */
  public ConnectorInstance getConnectorInstance(final Subscription sub) throws NoteException {
    ConnectorInstance cinst;
    Connector conn;

    cinst = sub.getSourceConnInst();
    conn = sub.getSourceConn();

    if (cinst != null) {
      return cinst;
    }

    if (conn == null) {
      throw new NoteException("No connector for " + sub);
    }

    cinst = conn.getConnectorInstance(sub);
    if (cinst == null) {
      throw new NoteException("No connector instance for " + sub);
    }

    sub.setSourceConnInst(cinst);

    return cinst;
  }

  /** When we start up a new subscription we implant a Connector in the object.
   *
   * @param sub
   * @throws org.bedework.notifier.exception.NoteException
   */
  public void setConnectors(final Subscription sub) throws NoteException {
    String connectorId = sub.getSourceConnectorInfo().getConnectorId();

    Connector conn = getConnector(connectorId);
    if (conn == null) {
      throw new NoteException("No connector for " + sub + "(");
    }

    sub.setSourceConn(conn);
  }

  private Collection<Connector> getConnectors() {
    return connectorMap.values();
  }

  /** Return a registered connector with the given id.
   *
   * @param id
   * @return connector or null.
   */
  public Connector getConnector(final String id) {
    return connectorMap.get(id);
  }

  /**
   * @return registered ids.
   */
  public Set<String> getConnectorIds() {
    return connectorMap.keySet();
  }

  private void registerConnector(final String id,
                                 final ConnectorConfig conf) throws NoteException {
    try {
      Class cl = Class.forName(conf.getConnectorClassName());

      if (connectorMap.containsKey(id)) {
        throw new NoteException("Connector " + id + " already registered");
      }

      Connector c = (Connector)cl.newInstance();
      connectorMap.put(id, c);
    } catch (Throwable t) {
      throw new NoteException(t);
    }
  }

  /** Processes a batch of notifications. This must be done in a timely manner
   * as a request is usually hanging on this.
   *
   * @param notes
   * @throws org.bedework.notifier.exception.NoteException
   */
  public void handleNotifications(
            final NotificationBatch<Notification> notes) throws NoteException {
    for (Notification note: notes.getNotifications()) {
      db.open();
      Noteling sl = null;

      try {
        if (note.getNotification() != null) {
          sl = notelingPool.get();

          handleNotification(sl, note);
        }
      } finally {
        db.close();
        if (sl != null) {
          notelingPool.add(sl);
        }
      }
    }

    return;
  }

  @SuppressWarnings("unchecked")
  private StatusType handleNotification(final Noteling sl,
                                        final Notification note) throws NoteException {
    StatusType st = sl.handleNotification(note);

    /*
    Subscription sub = note.getNotification();
    if (!sub.getMissingTarget()) {
      return st;
    }

    if (sub.getErrorCt() > getConfig().getMissingTargetRetries()) {
      deleteSubscription(sub);
      info("Subscription deleted after missing target retries exhausted: " + sub);
    }
    */

    return st;
  }

  /* ====================================================================
   *                        db methods
   * ==================================================================== */

  /**
   * @param id
   * @return subscription
   * @throws org.bedework.notifier.exception.NoteException
   */
  public Subscription getSubscription(final String id) throws NoteException {
    boolean opened = db.open();

    try {
      return db.get(id);
    } finally {
      if (opened) {
        // It's a one-shot
        db.close();
      }
    }
  }

  /**
   * @param sub
   * @throws org.bedework.notifier.exception.NoteException
   */
  public void addSubscription(final Subscription sub) throws NoteException {
    db.add(sub);
    sub.resetChanged();
  }

  /**
   * @param sub
   * @throws org.bedework.notifier.exception.NoteException
   */
  public void updateSubscription(final Subscription sub) throws NoteException {
    boolean opened = db.open();

    try {
      db.update(sub);
      sub.resetChanged();
    } finally {
      if (opened) {
        // It's a one-shot
        db.close();
      }
    }
  }

  /**
   * @param sub
   * @throws org.bedework.notifier.exception.NoteException
   */
  public void deleteSubscription(final Subscription sub) throws NoteException {
    db.delete(sub);
  }

  /** Find any subscription that matches this one. There can only be one with
   * the same endpoints
   *
   * @param sub
   * @return matching subscriptions
   * @throws org.bedework.notifier.exception.NoteException
   */
  public Subscription find(final Subscription sub) throws NoteException {
    boolean opened = db.open();

    try {
      return db.find(sub);
    } finally {
      if (opened) {
        // It's a one-shot
        db.close();
      }
    }
  }

  /* ====================================================================
   *                        private methods
   * ==================================================================== */

  private Logger getLogger() {
    if (log == null) {
      log = Logger.getLogger(this.getClass());
    }

    return log;
  }

  private void trace(final String msg) {
    getLogger().debug(msg);
  }

  private void warn(final String msg) {
    getLogger().warn(msg);
  }

  private void error(final String msg) {
    getLogger().error(msg);
  }

  private void error(final Throwable t) {
    getLogger().error(this, t);
  }

  private void info(final String msg) {
    getLogger().info(msg);
  }
}