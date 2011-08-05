/*
 * Copyright 2011 Martin Grotzke
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

import static de.javakaffee.web.msm.SessionValidityInfo.createValidityInfoKeyName;
import static de.javakaffee.web.msm.SessionValidityInfo.decode;
import static de.javakaffee.web.msm.SessionValidityInfo.encode;
import static de.javakaffee.web.msm.Statistics.StatsType.*;
import static java.lang.Math.min;
import static java.lang.Thread.sleep;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import net.spy.memcached.MemcachedClient;

import org.apache.catalina.connector.Request;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

import de.javakaffee.web.msm.BackupSessionTask.BackupResult;
import de.javakaffee.web.msm.MemcachedBackupSessionManager.LockStatus;
import de.javakaffee.web.msm.SessionTrackerValve.SessionBackupService.BackupResultStatus;

/**
 * Represents the session locking hooks that must be implemented by the various locking strategies.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public abstract class LockingStrategy {

    public static enum LockingMode {
        /** Sessions are never locked. */
        NONE,
        /** Sessions are locked for each request. */
        ALL,
        /** Readonly requests are tracked and for requests that modify the session the session is locked. */
        AUTO,
        /** The application explicitely manages locks */
        APP,
        /** The session is locked for configured request patterns **/
        URI_PATTERN
    }

    protected static final String LOCK_VALUE = "locked";
    //再次访问时间间隔（毫秒）
    protected static final int LOCK_RETRY_INTERVAL = 10;
    //再次访问最大时间间隔（毫秒）
    protected static final int LOCK_MAX_RETRY_INTERVAL = 500;
    //再次访问超时时间（秒）
    protected static final int LOCK_TIMEOUT = 2000;

    protected final Log _log = LogFactory.getLog( getClass() );

    protected MemcachedBackupSessionManager _manager;
    protected final MemcachedClient _memcached;
    protected LRUCache<String, Boolean> _missingSessionsCache;
    protected final SessionIdFormat _sessionIdFormat;
    protected final InheritableThreadLocal<Request> _requestsThreadLocal;
    private final ExecutorService _executor;
    //是否做备份信息
    private final boolean _storeSecondaryBackup;
    protected final Statistics _stats;

    protected LockingStrategy( @Nonnull final MemcachedBackupSessionManager manager,
            @Nonnull final MemcachedClient memcached,
            @Nonnull final LRUCache<String, Boolean> missingSessionsCache, final boolean storeSecondaryBackup,
            @Nonnull final Statistics stats ) {
        _manager = manager;
        _memcached = memcached;
        _missingSessionsCache = missingSessionsCache;
        _sessionIdFormat = new SessionIdFormat();
        _requestsThreadLocal = new InheritableThreadLocal<Request>();
        _storeSecondaryBackup = storeSecondaryBackup;
        _stats = stats;
        _executor = Executors.newFixedThreadPool( Runtime.getRuntime().availableProcessors() );
    }

    /**
     * Creates the appropriate {@link LockingStrategy} for the given {@link LockingMode}.
     */
    @CheckForNull
    public static LockingStrategy create( @Nullable final LockingMode lockingMode, @Nullable final Pattern uriPattern,
            @Nonnull final MemcachedClient memcached, @Nonnull final MemcachedBackupSessionManager manager,
            @Nonnull final LRUCache<String, Boolean> missingSessionsCache, final boolean storeSecondaryBackup,
            @Nonnull final Statistics stats ) {
        if ( lockingMode == null ) {
            return null;
        }
        switch ( lockingMode ) {
        case ALL:
            return new LockingStrategyAll( manager, memcached, missingSessionsCache, storeSecondaryBackup, stats );
        case AUTO:
            return new LockingStrategyAuto( manager, memcached, missingSessionsCache, storeSecondaryBackup, stats );
        case URI_PATTERN:
            return new LockingStrategyUriPattern( manager, uriPattern, memcached, missingSessionsCache, storeSecondaryBackup,
                    stats );
        case NONE:
            return new LockingStrategyNone( manager, memcached, missingSessionsCache, storeSecondaryBackup, stats );
        default:
            throw new IllegalArgumentException( "LockingMode not yet supported: " + lockingMode );
        }
    }

    /**
     * Shutdown this lockingStrategy, which frees all resources / releases threads.
     */
    public void shutdown() {
        _executor.shutdown();
    }

    protected LockStatus lock( final String sessionId ) {
        return lock( sessionId, LOCK_TIMEOUT, TimeUnit.MILLISECONDS );
    }

    /**
     * 初次锁定session
     * 锁定线程操作----将sessionid 放入memcached 中，标识为锁定。
     * @param sessionId			sessionId
     * @param timeout			超时时间
     * @param timeUnit			超时时间单位
     * @return
     */
    protected LockStatus lock( final String sessionId, final long timeout, final TimeUnit timeUnit ) {
        if ( _log.isDebugEnabled() ) {
            _log.debug( "Locking session " + sessionId );
        }
        final long start = System.currentTimeMillis();
        try {
            acquireLock( sessionId, LOCK_RETRY_INTERVAL, LOCK_MAX_RETRY_INTERVAL, timeUnit.toMillis( timeout ), System.currentTimeMillis() );
            _stats.registerSince( ACQUIRE_LOCK, start );
            if ( _log.isDebugEnabled() ) {
                _log.debug( "Locked session " + sessionId );
            }
            return LockStatus.LOCKED;
        } catch ( final TimeoutException e ) {
        	// 锁定超时
            _log.warn( "Reached timeout when trying to aquire lock for session " + sessionId
                    + ". Will use this session without this lock." );
            _stats.registerSince( ACQUIRE_LOCK_FAILURE, start );
            return LockStatus.COULD_NOT_AQUIRE_LOCK;
        } catch ( final InterruptedException e ) {
        	// 线程异常 中断线程
            Thread.currentThread().interrupt();
            throw new RuntimeException( "Got interrupted while trying to lock session.", e );
        } catch ( final ExecutionException e ) {
        	//无法锁定
            _log.warn( "An exception occurred when trying to aquire lock for session " + sessionId );
            _stats.registerSince( ACQUIRE_LOCK_FAILURE, start );
            return LockStatus.COULD_NOT_AQUIRE_LOCK;
        }
    }

    /**
     * 直到 将 key 为sessionid 的锁定对象放入memcached 成功后， 才结束该方法
     * 否则 ，休眠后，继续执行，一直尝试 放入锁定对象，直到超时（指定超时时间） 才终止。
     * @param sessionId					sessionId
     * @param retryInterval				再此访问时间
     * @param maxRetryInterval			最大再此访问时间
     * @param timeout					超时
     * @param start						起始时间
     * @throws InterruptedException
     * @throws ExecutionException
     * @throws TimeoutException
     */
    protected void acquireLock( @Nonnull final String sessionId, final long retryInterval, final long maxRetryInterval,
            final long timeout, final long start ) throws InterruptedException, ExecutionException, TimeoutException {
        //有返回结果，
    	final Future<Boolean> result = _memcached.add( _sessionIdFormat.createLockName( sessionId ), 5, LOCK_VALUE );
    	//执行成功
    	if ( result.get().booleanValue() ) {
            if ( _log.isDebugEnabled() ) {
                _log.debug( "Locked session " + sessionId );
            }
            return;
        }
        else {
        	//执行失败，休眠后，继续执行
            checkTimeoutAndWait( sessionId, retryInterval, maxRetryInterval, timeout, start );
            acquireLock( sessionId, retryInterval * 2, maxRetryInterval, timeout, start );
        }
    }

    /**
     * 如果未超时，则休眠指定时间 <b>timeToWait</b>
     * 否则抛出超时异常
     * @param sessionId					sessionId
     * @param retryInterval				再此访问时间
     * @param maxRetryInterval			最大再此访问时间
     * @param timeout					超时
     * @param start						起始时间
     */
    protected void checkTimeoutAndWait( @Nonnull final String sessionId, final long retryInterval,
            final long maxRetryInterval, final long timeout, final long start ) throws TimeoutException,
            InterruptedException {
    	//超时判断
        if ( System.currentTimeMillis() >= start + timeout ) {
            throw new TimeoutException( "Reached timeout when trying to aquire lock for session " + sessionId );
        }
        //最小间隔访问时间
        final long timeToWait = min( retryInterval, maxRetryInterval );
        if ( _log.isDebugEnabled() ) {
            _log.debug( "Could not aquire lock for session " + sessionId + ", waiting " + timeToWait + " millis now..." );
        }
        //休眠
        sleep( timeToWait );
    }

    /**
     * 释放锁，删除memcached 中的key为sessionid的对象
     * @param sessionId		sessionid
     */
    protected void releaseLock( @Nonnull final String sessionId ) {
        try {
            if ( _log.isDebugEnabled() ) {
                _log.debug( "Releasing lock for session " + sessionId );
            }
            final long start = System.currentTimeMillis();
            _memcached.delete( _sessionIdFormat.createLockName( sessionId ) );
            _stats.registerSince( RELEASE_LOCK, start );
        } catch ( final Exception e ) {
            _log.warn( "Caught exception when trying to release lock for session " + sessionId );
        }
    }

    /**
     * <p>更新有效性验证信息和备份的有效性验证信息</p>
     *  当前容器中没有session对象时，更新有效性验证信息，和 备份的有效性验证信息
     *  首先 更新有效性验证信息 "validity:" + sessionId;
     *  其次 更新备份的有效性验证信息"bak:" + "validity:" + sessionId; <br/>
     * Is invoked for the backup of a non-sticky session that was not accessed for the current request.
     */
    protected void onBackupWithoutLoadedSession( @Nonnull final String sessionId, @Nonnull final String requestId,
            @Nonnull final BackupSessionService backupSessionService ) {

        if ( !_sessionIdFormat.isValid( sessionId ) ) {
            return;
        }

        try {

            final long start = System.currentTimeMillis();
            //查找SessionValidityInfo有效性验证信息
            final String validityKey = createValidityInfoKeyName( sessionId );
            final SessionValidityInfo validityInfo = loadSessionValidityInfoForValidityKey( validityKey );
            if ( validityInfo == null ) {
                _log.warn( "Found no validity info for session id " + sessionId );
                return;
            }
            //更新有效性验证信息
            final int maxInactiveInterval = validityInfo.getMaxInactiveInterval();
            final byte[] validityData = encode( maxInactiveInterval, System.currentTimeMillis(), System.currentTimeMillis() );
            // fix for #88, along with the change in session.getMemcachedExpirationTimeToSet
            final int expiration = maxInactiveInterval <= 0 ? 0 : maxInactiveInterval;
            // 存入validityInfoKey至 memcached
            _memcached.set( validityKey, expiration, validityData );

            /*
             * - ping session
             * - ping session backup
             * - save validity backup
             */
            final Callable<?> backupSessionTask = new OnBackupWithoutLoadedSessionTask( sessionId,
                    _storeSecondaryBackup, validityKey, validityData, maxInactiveInterval );
            _executor.submit( backupSessionTask );

            if ( _log.isDebugEnabled() ) {
                _log.debug( "Stored session validity info for session " + sessionId );
            }

            _stats.registerSince( NON_STICKY_ON_BACKUP_WITHOUT_LOADED_SESSION, start );

        } catch( final Throwable e ) {
            _log.warn( "An error when trying to load/update validity info.", e );
        }

    }

    /**
     * <p>更新 session有效性验证信息，更新session信息，更新 备份 session信息</p>
     * Is invoked after the backup of the session is initiated, it's represented by the provided backupResult. The
     * requestId is identifying the request.
     */
    protected void onAfterBackupSession( @Nonnull final MemcachedBackupSession session, final boolean backupWasForced,
            @Nonnull final Future<BackupResult> result, @Nonnull final String requestId,
            @Nonnull final BackupSessionService backupSessionService ) {

    	//sessionid 检查
        if ( !_sessionIdFormat.isValid( session.getIdInternal() ) ) {
            return;
        }

        try {

            final long start = System.currentTimeMillis();
            
            // 更新 validityKey 
            final int maxInactiveInterval = session.getMaxInactiveInterval();
            final byte[] validityData = encode( maxInactiveInterval, session.getLastAccessedTimeInternal(), session.getThisAccessedTimeInternal() );
            final String validityKey = createValidityInfoKeyName( session.getIdInternal() );
            // fix for #88, along with the change in session.getMemcachedExpirationTimeToSet
            final int expiration = maxInactiveInterval <= 0 ? 0 : maxInactiveInterval;
            _memcached.set( validityKey, expiration, validityData );
            if ( _log.isDebugEnabled() ) {
                _log.debug( "Stored session validity info for session " + session.getIdInternal() );
            }

            /* The following task are performed outside of the request thread (includes waiting for the backup result):
             * - ping session if the backup was skipped (depends on the backup result)
             * - save secondary session backup if session was modified (backup not skipped)
             * - ping secondary session backup if the backup was skipped
             * - save secondary validity backup
             */
            final boolean pingSessionIfBackupWasSkipped = !backupWasForced;
            final boolean performAsyncTasks = pingSessionIfBackupWasSkipped || _storeSecondaryBackup;

            if ( performAsyncTasks ) {
                final Callable<?> backupSessionTask = new OnAfterBackupSessionTask( session, result,
                        pingSessionIfBackupWasSkipped, backupSessionService, _storeSecondaryBackup, validityKey, validityData );
                _executor.submit( backupSessionTask );
            }

            _stats.registerSince( NON_STICKY_AFTER_BACKUP, start );

        } catch( final Throwable e ) {
            _log.warn( "An error occurred during onAfterBackupSession.", e );
        }

    }

    /**
     * 
     * Is used to determine if this thread / the current request already hit the application or if this method
     * invocation comes from the container.
     */
    protected final boolean isContainerSessionLookup() {
        return _requestsThreadLocal.get() == null;
    }

    /**
     * 加载有效验证信息 key： "validity:" + sessionId <br/> 信息
     * @param sessionId
     * @return
     */
    @CheckForNull
    protected SessionValidityInfo loadSessionValidityInfo( @Nonnull final String sessionId ) {
        return loadSessionValidityInfoForValidityKey( createValidityInfoKeyName( sessionId ) );
    }

    /**
     * 从 memcached 查找  备份的session 有效性（"validity:" + sessionid） 对象
     * @param validityInfoKey
     * @return
     */
    @CheckForNull
    protected SessionValidityInfo loadSessionValidityInfoForValidityKey( @Nonnull final String validityInfoKey ) {
        final byte[] validityInfo = (byte[]) _memcached.get( validityInfoKey );
        return validityInfo != null ? decode( validityInfo ) : null;
    }

    /**
     * 根据sessionid查找 ("bak:" + "validity:" + sessionid)备份的有效性session 对象
     * @param sessionId
     * @return
     */
    @CheckForNull
    protected SessionValidityInfo loadBackupSessionValidityInfo( @Nonnull final String sessionId ) {
        final String key = createValidityInfoKeyName( sessionId );
        final String backupKey = _sessionIdFormat.createBackupKey( key );
        return loadSessionValidityInfoForValidityKey( backupKey );
    }

    /**
     * 仅作锁定操作！！！执行锁定操作，即将 该key（"lock:" + sessionId）存放于memcached中
     * 即标识位将该session已锁定
     * Invoked before the session for this sessionId is loaded from memcached.
     */
    @CheckForNull
    protected abstract LockStatus onBeforeLoadFromMemcached( @Nonnull String sessionId ) throws InterruptedException,
            ExecutionException;

    /**
     * 从memcached中加载有效验证信息 key： "validity:" + sessionId <br/> 信息
     * 并将LastAccessedTime, ThisAccessedTime 赋予session对象
     * Invoked after a non-sticky session is loaded from memcached, can be used to update some session fields based on
     * separately stored information (e.g. session validity info).
     *
     * @param lockStatus
     *            the {@link LockStatus} that was returned from {@link #onBeforeLoadFromMemcached(String)}.
     */
    protected void onAfterLoadFromMemcached( @Nonnull final MemcachedBackupSession session,
            @Nullable final LockStatus lockStatus ) {
        session.setLockStatus( lockStatus );

        final long start = System.currentTimeMillis();
        final SessionValidityInfo info = loadSessionValidityInfo( session.getIdInternal() );
        if ( info != null ) {
            _stats.registerSince( NON_STICKY_AFTER_LOAD_FROM_MEMCACHED, start );
            session.setLastAccessedTimeInternal( info.getLastAccessedTime() );
            session.setThisAccessedTimeInternal( info.getThisAccessedTime() );
        }
        else {
            _log.warn( "No validity info available for session " + session.getIdInternal() );
        }
    }

    /**
     * manager 中调用在删除memcache中 sessionid 对象后，
     * 删除 "validity:" + sessionId; 有效性  对象 
     * 删除 "bak:" + sessionId;
     * 删除 "bak:" + "validity:" + sessionId;
     * @param sessionId
     */
    /**
     * Invoked after a non-sticky session is removed from memcached.
     */
    protected void onAfterDeleteFromMemcached( @Nonnull final String sessionId ) {
        final long start = System.currentTimeMillis();

        final String validityInfoKey = createValidityInfoKeyName( sessionId );
        _memcached.delete( validityInfoKey );

        if (_storeSecondaryBackup) {
            _memcached.delete( _sessionIdFormat.createBackupKey( sessionId ) );
            _memcached.delete( _sessionIdFormat.createBackupKey( validityInfoKey ) );
        }

        _stats.registerSince( NON_STICKY_AFTER_DELETE_FROM_MEMCACHED, start );
    }

    protected final void onRequestStart( final Request request ) {
        _requestsThreadLocal.set( request );
    }

    protected final void onRequestFinished() {
        _requestsThreadLocal.set( null );
    }

    /**
     * 测试 该sessionId 在memcached 中是否存在
     * @param sessionId
     * @return
     * @throws InterruptedException
     */
    private boolean pingSession( @Nonnull final String sessionId ) throws InterruptedException {
        final Future<Boolean> touchResult = _memcached.add( sessionId, 1, 1 );
        try {
            _log.debug( "Got ping result " + touchResult.get() );
            //不存在
            if ( touchResult.get() ) {
                _stats.nonStickySessionsPingFailed();
                _log.warn( "The session " + sessionId
                        + " should be touched in memcached, but it seemed to be"
                        + " not existing anymore." );
                return false;
            }
            return true;
        } catch ( final ExecutionException e ) {
            _log.warn( "An exception occurred when trying to ping session " + sessionId, e );
            return false;
        }
    }
    
    /**
     * 当memcached中不包含当前session时，对session做更新操作。
     * 否则 不执行更新操作
     * @param session
     * @param backupSessionService
     * @throws InterruptedException
     */
    private void pingSession( @Nonnull final MemcachedBackupSession session,
            @Nonnull final BackupSessionService backupSessionService ) throws InterruptedException {
        final Future<Boolean> touchResult = _memcached.add( session.getIdInternal(), 5, 1 );
        try {
            _log.debug( "Got ping result " + touchResult.get() );
            if ( touchResult.get() ) {
                _stats.nonStickySessionsPingFailed();
                _log.warn( "The session " + session.getIdInternal()
                        + " should be touched in memcached, but it seemed to be"
                        + " not existing anymore. Will store in memcached again." );
                updateSession( session, backupSessionService );
            }
        } catch ( final ExecutionException e ) {
            _log.warn( "An exception occurred when trying to ping session " + session.getIdInternal(), e );
        }
    }

    /**
     * 对memcached session对象 更新
     * @param session
     * @param backupSessionService
     * @throws InterruptedException
     */
    private void updateSession( @Nonnull final MemcachedBackupSession session,
            @Nonnull final BackupSessionService backupSessionService ) throws InterruptedException {
        final Future<BackupResult> result = backupSessionService.backupSession( session, true );
        try {
            if ( result.get().getStatus() != BackupResultStatus.SUCCESS ) {
                _log.warn( "Update for session (after unsuccessful ping) did not return SUCCESS, but " + result.get() );
            }
        } catch ( final ExecutionException e ) {
            _log.warn( "An exception occurred when trying to update session " + session.getIdInternal(), e );
        }
    }

    /**
     * session 备份后执行的操作
     */
    private final class OnAfterBackupSessionTask implements Callable<Void> {

        private final MemcachedBackupSession _session;
        private final Future<BackupResult> _result;
        private final boolean _pingSessionIfBackupWasSkipped;
        private final boolean _storeSecondaryBackup;
        private final BackupSessionService _backupSessionService;
        private final String _validityKey;
        private final byte[] _validityData;

        /**
         * session 备份后执行的操作
         */
        private OnAfterBackupSessionTask( @Nonnull final MemcachedBackupSession session, @Nonnull final Future<BackupResult> result,
                final boolean pingSessionIfBackupWasSkipped,
                @Nonnull final BackupSessionService backupSessionService,
                final boolean storeSecondaryBackup,
                @Nonnull final String validityKey,
                @Nonnull final byte[] validityData ) {
            _session = session;
            _result = result;
            _pingSessionIfBackupWasSkipped = pingSessionIfBackupWasSkipped;
            _storeSecondaryBackup = storeSecondaryBackup;
            _validityKey = validityKey;
            _validityData = validityData;
            _backupSessionService = backupSessionService;
        }

        @Override
        public Void call() throws Exception {

            final BackupResult backupResult = _result.get();
            
            if ( _pingSessionIfBackupWasSkipped ) {
                if ( backupResult.getStatus() == BackupResultStatus.SKIPPED ) {
                    pingSession( _session, _backupSessionService );
                }
            }

            /*
             * For non-sticky sessions we store a backup of the session in a secondary memcached node (under a special key
             * that's resolved by the SuffixBasedNodeLocator), but only when we have more than 1 memcached node configured...
             */
            //备份操作
            if ( _storeSecondaryBackup ) {
                try {
                    if ( _log.isDebugEnabled() ) {
                        _log.debug( "Storing backup in secondary memcached for non-sticky session " + _session.getId() );
                    }
                    // 前一个操作被忽略的情况下
                    //备份session
                    if ( backupResult.getStatus() == BackupResultStatus.SKIPPED ) {
                        pingSessionBackup( _session );
                    }
                    //直接更新 备份session
                    else {
                        saveSessionBackupFromResult( backupResult );
                    }
                    //更新备份的有效性验证信息
                    saveValidityBackup();
                } catch( final NodeFailureException e ) {
                    // handle an unavailable secondary/backup node (fix for issue #83)
                    _log.info( "Secondary/backup node "+ e.getNodeId() +" not available, skipping additional backup of session " + _session.getIdInternal() );
                } catch( final RuntimeException e ) {
                    _log.info( "Could not store secondary backup of session " + _session.getIdInternal(), e );
                }

            }

            return null;
        }

        /**
         *  重新在memcached中放入 session 操作 
         * @param backupResult
         */
        public void saveSessionBackupFromResult( final BackupResult backupResult ) {
            final byte[] data = backupResult.getData();
            if ( data != null ) {
                final String key = _sessionIdFormat.createBackupKey( _session.getId() );
                _memcached.set( key, _session.getMemcachedExpirationTimeToSet(), data );
            }
            else {
                _log.warn( "No data set for backupResultStatus " + backupResult.getStatus() + " for sessionId "
                        + _session.getIdInternal() + ", skipping backup"
                        + " of non-sticky session in secondary memcached." );
            }
        }

        /**
         * 保存 "bak: " + "validity:" + sessionid;
         * 有效性备份session
         */
        public void saveValidityBackup() {
            final String backupValidityKey = _sessionIdFormat.createBackupKey( _validityKey );
            final int maxInactiveInterval = _session.getMaxInactiveInterval();
            // fix for #88, along with the change in session.getMemcachedExpirationTimeToSet
            final int expiration = maxInactiveInterval <= 0 ? 0 : maxInactiveInterval;
            _memcached.set( backupValidityKey, expiration, _validityData );
        }

        /**
         * 测试  是否包含 "bak:" + sessionid 
         * 不存在的情况下执行 session 备份
         * @param session
         * @throws InterruptedException
         */
        private void pingSessionBackup( @Nonnull final MemcachedBackupSession session ) throws InterruptedException {
            final String key = _sessionIdFormat.createBackupKey( session.getId() );
            final Future<Boolean> touchResultFuture = _memcached.add( key, 5, 1 );
            try {
                final boolean touchResult = touchResultFuture.get(200, TimeUnit.MILLISECONDS);
                _log.debug( "Got backup ping result " + touchResult );
                if ( touchResult ) {
                    _log.warn( "The secondary backup for session " + session.getIdInternal()
                            + " should be touched in memcached, but it seemed to be"
                            + " not existing. Will store in memcached again." );
                    saveSessionBackup( session, key );
                }
            } catch ( final TimeoutException e ) {
                _log.warn( "The secondary backup for session " + session.getIdInternal()
                        + " could not be completed within 200 millis, was cancelled now." );
            } catch ( final ExecutionException e ) {
                _log.warn( "An exception occurred when trying to ping session " + session.getIdInternal(), e );
            }
        }

        /**
         * 备份 session 至 memcachesd
         * @param session
         * @param key
         * @throws InterruptedException
         */
        public void saveSessionBackup( @Nonnull final MemcachedBackupSession session, @Nonnull final String key )
                throws InterruptedException {
            try {
                final byte[] data = _manager.serialize( session );
                final Future<Boolean> backupResult = _memcached.set( key, session.getMemcachedExpirationTimeToSet(), data );
                if ( !backupResult.get().booleanValue() ) {
                    _log.warn( "Update for secondary backup of session "+ session.getIdInternal() +" (after unsuccessful ping) did not return sucess." );
                }
            } catch ( final ExecutionException e ) {
                _log.warn( "An exception occurred when trying to update secondary session backup for " + session.getIdInternal(), e );
            }
        }
    }

    /**
     * <p>更新有效性验证信息和备份的有效性验证信息</p>
     * 仅作以下操作：memcached 中set 有效性验证信息备份："bak:" + "validity:" + sessionId;
     * 测试 memcached中是否包含_sessionId;
     * 测试 memcached中是否包含"bak:" + sessionId;
     * 更新 memcached中包含的 有效性验证备份信息"bak:" + "validity:" + sessionId;
     */
    private final class OnBackupWithoutLoadedSessionTask implements Callable<Void> {

        private final String _sessionId;
        private final boolean _storeSecondaryBackup;
        private final String _validityKey;
        private final byte[] _validityData;
        private final int _maxInactiveInterval;

        /**
         * <p>更新备份的有效性验证信息</p>
         * 仅作以下操作：memcached 中set 有效性验证信息备份："bak:" + "validity:" + sessionId;
         * 测试 memcached中是否包含_sessionId;
         * 测试 memcached中是否包含"bak:" + sessionId;
         * 更新 memcached中包含的 有效性验证备份信息"bak:" + "validity:" + sessionId;
         */
        private OnBackupWithoutLoadedSessionTask( @Nonnull final String sessionId,
                final boolean storeSecondaryBackup,
                @Nonnull final String validityKey,
                @Nonnull final byte[] validityData,
                final int maxInactiveInterval ) {
            _sessionId = sessionId;
            _storeSecondaryBackup = storeSecondaryBackup;
            _validityKey = validityKey;
            _validityData = validityData;
            _maxInactiveInterval = maxInactiveInterval;
        }

        @Override
        public Void call() throws Exception {
        	
        	// memcached 是否包含session对象
            pingSession( _sessionId );

            /*
             * For non-sticky sessions we store/ping a backup of the session in a secondary memcached node (under a special key
             * that's resolved by the SuffixBasedNodeLocator), but only when we have more than 1 memcached node configured...
             */
            //是否做备份信息标识
            if ( _storeSecondaryBackup ) {
                try {
                	//是否包含备份的session
                    pingSessionBackup( _sessionId );

                    final String backupValidityKey = _sessionIdFormat.createBackupKey( _validityKey );
                    // fix for #88, along with the change in session.getMemcachedExpirationTimeToSet
                    final int expiration = _maxInactiveInterval <= 0 ? 0 : _maxInactiveInterval;
                    //更新备份后的有效性session
                    _memcached.set( backupValidityKey, expiration, _validityData );

                } catch( final NodeFailureException e ) {
                    // handle an unavailable secondary/backup node (fix for issue #83)
                    _log.info( "Secondary/backup node "+ e.getNodeId() +" not available, skipping additional ping of session " + _sessionId );
                } catch( final RuntimeException e ) {
                    _log.info( "Could not store secondary backup of session " + _sessionId, e );
                }

            }

            return null;
        }

        /**
         * 测试memcached中是否包含  key ： （"bak:" + sessionId）
         * 如果存在，则返回false， 反之 true
         * @param sessionId	sessionId
         * @return
         * @throws InterruptedException
         */
        private boolean pingSessionBackup( @Nonnull final String sessionId ) throws InterruptedException {
            final String key = _sessionIdFormat.createBackupKey( sessionId );
            final Future<Boolean> touchResultFuture = _memcached.add( key, 1, 1 );
            try {
                final boolean touchResult = touchResultFuture.get(200, TimeUnit.MILLISECONDS);
                _log.debug( "Got backup ping result " + touchResult );
                if ( touchResult ) {
                    _log.warn( "The secondary backup for session " + sessionId
                            + " should be touched in memcached, but it seemed to be"
                            + " not existing." );
                    return false;
                }
                return true;
            } catch ( final TimeoutException e ) {
                _log.warn( "The secondary backup for session " + sessionId
                        + " could not be completed within 200 millis, was cancelled now." );
                return false;
            } catch ( final ExecutionException e ) {
                _log.warn( "An exception occurred when trying to ping session " + sessionId, e );
                return false;
            }
        }
    }

    // ---------------- for testing

    @Nonnull
    ExecutorService getExecutorService() {
        return _executor;
    }

}
