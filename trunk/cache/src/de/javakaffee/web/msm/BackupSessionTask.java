/*
 * Copyright 2009 Martin Grotzke
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an &quot;AS IS&quot; BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package de.javakaffee.web.msm;


import static de.javakaffee.web.msm.Statistics.StatsType.ATTRIBUTES_SERIALIZATION;
import static de.javakaffee.web.msm.Statistics.StatsType.BACKUP;
import static de.javakaffee.web.msm.Statistics.StatsType.MEMCACHED_UPDATE;
import static de.javakaffee.web.msm.Statistics.StatsType.RELEASE_LOCK;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

import net.spy.memcached.MemcachedClient;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

import de.javakaffee.web.msm.BackupSessionTask.BackupResult;
import de.javakaffee.web.msm.SessionTrackerValve.SessionBackupService.BackupResultStatus;

/**
 * Stores the provided session in memcached if the session was modified
 * or if the session needs to be relocated (set <code>force</code> to <code>true</code>).
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class BackupSessionTask implements Callable<BackupResult> {

    private static final Log _log = LogFactory.getLog( BackupSessionTask.class );

    private final SessionIdFormat _sessionIdFormat = new SessionIdFormat();

    private final MemcachedBackupSession _session;
    private final boolean _force;
    private final TranscoderService _transcoderService;
    private final boolean _sessionBackupAsync;
    private final int _sessionBackupTimeout;
    private final MemcachedClient _memcached;
    private final NodeIdService _nodeIdService;
    private final Statistics _statistics;

    /**
     * @param session
     *            the session to save
     * @param sessionBackupAsync
     * @param sessionBackupTimeout
     * @param memcached
     * @param force
     *            specifies, if the session needs to be saved by all means, e.g.
     *            as it has to be relocated to another memcached
     *            node (the session id had been changed before in this case).
     * @param nodeAvailabilityCache
     * @param nodeIds
     * @param failoverNodeIds
     */
    public BackupSessionTask( final MemcachedBackupSession session,
            final boolean sessionIdChanged,
            final TranscoderService transcoderService,
            final boolean sessionBackupAsync,
            final int sessionBackupTimeout,
            final MemcachedClient memcached,
            final NodeIdService nodeIdService,
            final Statistics statistics ) {
        _session = session;
        _force = sessionIdChanged;
        _transcoderService = transcoderService;
        _sessionBackupAsync = sessionBackupAsync;
        _sessionBackupTimeout = sessionBackupTimeout;
        _memcached = memcached;
        _nodeIdService = nodeIdService;
        _statistics = statistics;
    }

    /**
     * {@inheritDoc}
     */
    public BackupResult call() throws Exception {
        if ( _log.isDebugEnabled() ) {
            _log.debug( "Starting for session id " + _session.getId() );
        }

        _session.setBackupRunning( true );
        try {

            final long startBackup = System.currentTimeMillis();

            final Map<String, Object> attributes = _session.getAttributesInternal();

            final byte[] attributesData = serializeAttributes( _session, attributes );
            final int hashCode = Arrays.hashCode( attributesData );
            final BackupResult result;
            if ( _session.getDataHashCode() != hashCode
                    || _force
                    || _session.authenticationChanged() ) {

                _session.setLastBackupTime( System.currentTimeMillis() );
                final byte[] data = _transcoderService.serialize( _session, attributesData );

                result = doBackupSession( _session, data, attributesData );
                if ( result.isSuccess() ) {
                    _session.setDataHashCode( hashCode );
                }
            } else {
                result = new BackupResult( BackupResultStatus.SKIPPED );
            }

            switch ( result.getStatus() ) {
                case FAILURE:
                    _statistics.requestWithBackupFailure();
                    _session.backupFailed();
                    break;
                case SKIPPED:
                    _statistics.requestWithoutSessionModification();
                    _session.storeThisAccessedTimeFromLastBackupCheck();
                    break;
                case SUCCESS:
                    _statistics.registerSince( BACKUP, startBackup );
                    _session.storeThisAccessedTimeFromLastBackupCheck();
                    _session.backupFinished();
                    break;
            }

            if ( _log.isDebugEnabled() ) {
                _log.debug( "Finished for session id " + _session.getId() +
                        ", returning status " + result );
            }

            return result;

        } finally {
            _session.setBackupRunning( false );
            releaseLock();
        }

    }

    private void releaseLock() {
        if ( _session.isLocked()  ) {
            try {
                if ( _log.isDebugEnabled() ) {
                    _log.debug( "Releasing lock for session " + _session.getIdInternal() );
                }
                final long start = System.currentTimeMillis();
                _memcached.delete( _sessionIdFormat.createLockName( _session.getIdInternal() ) );
                _statistics.registerSince( RELEASE_LOCK, start );
                _session.releaseLock();
            } catch( final Exception e ) {
                _log.warn( "Caught exception when trying to release lock for session " + _session.getIdInternal() );
            }
        }
    }

    private byte[] serializeAttributes( final MemcachedBackupSession session, final Map<String, Object> attributes ) {
        final long start = System.currentTimeMillis();
        final byte[] attributesData = _transcoderService.serializeAttributes( session, attributes );
        _statistics.registerSince( ATTRIBUTES_SERIALIZATION, start );
        return attributesData;
    }

    /**
     * Store the provided session in memcached.
     * @param session the session to backup
     * @param data the serialized session data (session fields and session attributes).
     * @param attributesData just the serialized session attributes.
     *
     * @return the {@link SessionTrackerValve.SessionBackupService.BackupResultStatus}
     */
    BackupResult doBackupSession( final MemcachedBackupSession session, final byte[] data, final byte[] attributesData ) {
        if ( _log.isDebugEnabled() ) {
            _log.debug( "Trying to store session in memcached: " + session.getId() );
        }

        try {

            storeSessionInMemcached( session, data );

            return new BackupResult( BackupResultStatus.SUCCESS, data, attributesData );
        } catch ( final NodeFailureException e ) {
            if ( _log.isInfoEnabled() ) {
                String msg = "Could not store session " + session.getId() +
                        " in memcached due to unavailable node " + e.getNodeId() + ".";
                if ( _force ) {
                    msg += "\nNote that this session was relocated to this node because the original node was not available.";
                }
                _log.info( msg );
            }

            return new BackupResult( BackupResultStatus.FAILURE, data, null );
        }
    }

    private void storeSessionInMemcached( final MemcachedBackupSession session, final byte[] data) throws NodeFailureException {

        /* calculate the expiration time (instead of using just maxInactiveInterval), as
         * this is relevant for the update of the expiration time: if we would just use
         * maxInactiveInterval, the session would exist longer in memcached than it would
         * be valid in tomcat
         */
        final int expirationTime = session.getMemcachedExpirationTimeToSet();
        final long start = System.currentTimeMillis();
        try {
            final Future<Boolean> future = _memcached.set( session.getId(), expirationTime, data );
            if ( !_sessionBackupAsync ) {
                try {
                    future.get( _sessionBackupTimeout, TimeUnit.MILLISECONDS );
                    session.setLastMemcachedExpirationTime( expirationTime );
                    session.setLastBackupTime( System.currentTimeMillis() );
                } catch ( final Exception e ) {
                    if ( _log.isInfoEnabled() ) {
                        _log.info( "Could not store session " + session.getId() + " in memcached." );
                    }
                    final String nodeId = _sessionIdFormat.extractMemcachedId( session.getId() );
                    _nodeIdService.setNodeAvailable( nodeId, false );
                    throw new NodeFailureException( "Could not store session in memcached.", nodeId );
                }
            }
            else {
                /* in async mode, we asume the session was stored successfully
                 */
                session.setLastMemcachedExpirationTime( expirationTime );
                session.setLastBackupTime( System.currentTimeMillis() );
            }
        } finally {
            _statistics.registerSince( MEMCACHED_UPDATE, start );
        }
    }

    static final class BackupResult {

        public static final BackupResult SKIPPED = new BackupResult( BackupResultStatus.SKIPPED );
        public static final BackupResult FAILURE = new BackupResult( BackupResultStatus.FAILURE );

        private final BackupResultStatus _status;
        private final byte[] _data;
        private final byte[] _attributesData;
        public BackupResult( @Nonnull final BackupResultStatus status ) {
            _status = status;
            _data = null;
            _attributesData = null;
        }
        public BackupResult( @Nonnull final BackupResultStatus status, @Nonnull final byte[] data, @Nonnull final byte[] attributesData ) {
            _status = status;
            _data = data;
            _attributesData = attributesData;
        }
        /**
         * The status/result of the backup operation.
         * @return the status
         */
        @Nonnull
        BackupResultStatus getStatus() {
            return _status;
        }
        /**
         * The serialized session data (session fields and session attributes).
         * This can be <code>null</code> (if {@link #getStatus()} is {@link BackupResultStatus#SKIPPED}).
         *
         * @return the session data
         */
        @CheckForNull
        byte[] getData() {
            return _data;
        }
        /**
         * The serialized attributes that were actually stored in memcached with the
         * full serialized session data. This can be <code>null</code>, e.g. if
         * {@link #getStatus()} is {@link BackupResultStatus#FAILURE} or {@link BackupResultStatus#SKIPPED}.
         *
         * @return the attributesData
         */
        @CheckForNull
        byte[] getAttributesData() {
            return _attributesData;
        }
        /**
         * @return <code>true</code> if the status is {@link BackupResultStatus#SUCCESS},
         * otherwise <code>false</code>.
         */
        public boolean isSuccess() {
            return _status == BackupResultStatus.SUCCESS;
        }
    }

}
