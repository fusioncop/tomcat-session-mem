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


import static de.javakaffee.web.msm.Statistics.StatsType.*;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import net.spy.memcached.ConnectionFactory;
import net.spy.memcached.MemcachedClient;
import net.spy.memcached.transcoders.SerializingTranscoder;

import org.apache.catalina.Container;
import org.apache.catalina.Context;
import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleListener;
import org.apache.catalina.Manager;
import org.apache.catalina.Session;
import org.apache.catalina.session.ManagerBase;
import org.apache.catalina.session.StandardSession;
import org.apache.catalina.util.LifecycleSupport;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

import de.javakaffee.web.msm.BackupSessionService.SimpleFuture;
import de.javakaffee.web.msm.BackupSessionTask.BackupResult;
import de.javakaffee.web.msm.LockingStrategy.LockingMode;
import de.javakaffee.web.msm.NodeAvailabilityCache.CacheLoader;
import de.javakaffee.web.msm.NodeIdResolver.MapBasedResolver;
import de.javakaffee.web.msm.SessionTrackerValve.SessionBackupService;

/**
 * This {@link Manager} stores session in configured memcached nodes after the
 * response is finished (committed).
 * <p>
 * Use this session manager in a Context element, like this <code><pre>
 * &lt;Context path="/foo"&gt;
 *     &lt;Manager className="de.javakaffee.web.msm.MemcachedBackupSessionManager"
 *         memcachedNodes="n1.localhost:11211 n2.localhost:11212" failoverNodes="n2"
 *         requestUriIgnorePattern=".*\.(png|gif|jpg|css|js)$" /&gt;
 * &lt;/Context&gt;
 * </pre></code>
 * </p>
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 * @version $Id$
 */
public class MemcachedBackupSessionManager extends ManagerBase implements Lifecycle, SessionBackupService, PropertyChangeListener {

    protected static final String NAME = MemcachedBackupSessionManager.class.getSimpleName();

    //版本
    private static final String INFO = NAME + "/1.0";

    //memcachedNodes 正则
    private static final String NODE_REGEX = "([\\w]+):([^:]+):([\\d]+)";
    //memcachedNodes 正则 Pattern 对象
    private static final Pattern NODE_PATTERN = Pattern.compile( NODE_REGEX );
    //多个 memcachedNodes 正则
    private static final String NODES_REGEX = NODE_REGEX + "(?:(?:\\s+|,)" + NODE_REGEX + ")*";
  //多个 memcachedNodes 正则 Pattern 对象
    private static final Pattern NODES_PATTERN = Pattern.compile( NODES_REGEX );

    //memcached 有效性检查的间隔时间 （毫秒）
    private static final int NODE_AVAILABILITY_CACHE_TTL = 50;

    private static final String PROTOCOL_TEXT = "text";
    private static final String PROTOCOL_BINARY = "binary";

    protected static final String NODE_FAILURE = "node.failure";

    protected final Log _log = LogFactory.getLog( getClass() );

    private final LifecycleSupport _lifecycle = new LifecycleSupport( this );

    /**
     * 是否启动标识
     */
    protected boolean _started = false;

    private final SessionIdFormat _sessionIdFormat = new SessionIdFormat();

    // -------------------- configuration properties --------------------

    /**
     * 配置文件中memcached 节点名称：ip：port
     *  e.g. n1:localhost:11211 n2:localhost:11212
     *	多个节点之间以<b>&nbsp;</b>为分隔符
     */
    private String _memcachedNodes;

    /**
     * 配置文件中失效的节点，<code>n1 |,n2</code> 多个节点之间以<b>&nbsp;|,</b>为分隔符
     * The ids of memcached failover nodes separated by space, e.g.
     * <code>n1 |,n2</code>
     *
     */
    private String _failoverNodes;

    /**
     * 符合正则的请求将被排除备份session, e.g.
     * <code>.*\.(png|gif|jpg|css|js)$</code>. Is matched against
     * request.getRequestURI.
     */
    private String _requestUriIgnorePattern;

    /**
     * 存入memecached的动作是异步还是同步
     * if true 异步操作	
     * if flase 同步操作 检查是否在指定的超时时间内（<b>_sessionBackupTimeout</b>）
     * 执行完该动作，否则认为是未执行成功
     */
    private boolean _sessionBackupAsync = true;

    /**
     * 存入memecached的超时时间 单位为<code>毫秒</code>
     * The default value is <code>100</code> millis.
     */
    private int _sessionBackupTimeout = 100;

    /**
     * 序列化工具类工厂类名称
     * The class name of the factory for
     * {@link net.spy.memcached.transcoders.Transcoder}s. Default class name is
     * {@link JavaSerializationTranscoderFactory}.
     */
    private String _transcoderFactoryClassName = JavaSerializationTranscoderFactory.class.getName();

    /**
     * 序列化集合？ 本版本无作用<br/>
     * Specifies, if iterating over collection elements shall be done on a copy
     * of the collection or on the collection itself.
     * <p>
     * This option can be useful if you have multiple requests running in
     * parallel for the same session (e.g. AJAX) and you are using
     * non-thread-safe collections (e.g. {@link java.util.ArrayList} or
     * {@link java.util.HashMap}). In this case, your application might modify a
     * collection while it's being serialized for backup in memcached.
     * </p>
     * <p>
     * <strong>Note:</strong> This must be supported by the TranscoderFactory
     * specified via {@link #setTranscoderFactoryClass(String)}.
     * </p>
     */
    private boolean _copyCollectionsForSerialization = false;
    //自定义转换类
    private String _customConverterClassNames;
	// Statistics 控制
    private boolean _enableStatistics = true;
    
    //备份线程
    private int _backupThreadCount = Runtime.getRuntime().availableProcessors();

    //memcache 协议
    private String _memcachedProtocol = PROTOCOL_TEXT;

    // memcacheClient 总开关，是否启用
    private final AtomicBoolean _enabled = new AtomicBoolean( true );

    // -------------------- END configuration properties --------------------
    
    // Statistics
    protected Statistics _statistics;

    /*
     * the memcached client
     */
    private MemcachedClient _memcached;

    /*
     * 缓存 缓存中没有被命中的session对象标识
     * findSession may be often called in one request. If a session is requested
     * that we don't have locally stored each findSession invocation would
     * trigger a memcached request - this would open the door for DOS attacks...
     *
     * this solution: use a LRUCache with a timeout to store, which session had
     * been requested in the last <n> millis.
     */
    private LRUCache<String, Boolean> _missingSessionsCache;

    private NodeIdService _nodeIdService;

    //private LRUCache<String, String> _relocatedSessions;

    /**
     * 最大并发session 如果为-1 则不增长
     * The maximum number of active Sessions allowed, or -1 for no limit.
     */
    private int _maxActiveSessions = -1;
    
    //拒绝的session个数
    private int _rejectedSessions;

    // sessionattribute 序列化管理工具 
    protected TranscoderService _transcoderService;

    private TranscoderFactory _transcoderFactory;
    
    //session序列化管理工具 
    private SerializingTranscoder _upgradeSupportTranscoder;
    //备份memcache
    private BackupSessionService _backupSessionService;
    
    
   /**
    * 如果设置为false 会话超时检查将不执行
    * 如果设置为false request跟踪（SessionTrackerValve）将不检查tomcat session
    * 
    */
    private boolean _sticky = true;
    private String _lockingMode;
    private LockingStrategy _lockingStrategy;
    
    //启动时，初始化加入容器中
    private SessionTrackerValve _sessionTrackerValve;

	static enum LockStatus {
        /**
         * For sticky sessions or readonly requests with non-sticky sessions there's no lock required.
         */
        LOCK_NOT_REQUIRED,
        LOCKED,
        COULD_NOT_AQUIRE_LOCK
    }

    /**
     * Return descriptive information about this Manager implementation and the
     * corresponding version number, in the format
     * <code>&lt;description&gt;/&lt;version&gt;</code>.
     *
     * @return the info string
     */
    @Override
    public String getInfo() {
        return INFO;
    }

    /**
     * Return the descriptive short name of this Manager implementation.
     *
     * @return the short name
     */
    @Override
    public String getName() {
        return NAME;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void init() {
        super.init();
    }

    /**
     * 容器初始化 方法 	<br/>
     * Initialize this manager. The memcachedClient parameter is there for testing
     * purposes. If the memcachedClient is provided it's used, otherwise a "real"/new
     * memcached client is created based on the configuration (like {@link #setMemcachedNodes(String)} etc.).
     *
     * @param memcachedClient the memcached client to use, for normal operations this should be <code>null</code>.
     */
    void startInternal( final MemcachedClient memcachedClient ) throws LifecycleException {
        _log.info( getClass().getSimpleName() + " starts initialization... (configured" +
                " nodes definition " + _memcachedNodes + ", failover nodes " + _failoverNodes + ")" );

        _statistics = Statistics.create( _enableStatistics );

        /* init memcached
         */
        final MemcachedConfig config = createMemcachedConfig( _memcachedNodes, _failoverNodes );
        _memcached = memcachedClient != null ? memcachedClient : createMemcachedClient( config.getNodeIds(), config.getAddresses(),
                config.getAddress2Ids(), _statistics );
        _nodeIdService = new NodeIdService( createNodeAvailabilityCache( config.getCountNodes(), NODE_AVAILABILITY_CACHE_TTL, _memcached ),
                config.getNodeIds(), config.getFailoverNodeIds() );

        /* create the missing sessions cache
         */
        _missingSessionsCache = new LRUCache<String, Boolean>( 200, 500 );
        
        //排除不需要过滤的后缀名
        _sessionTrackerValve = new SessionTrackerValve( _requestUriIgnorePattern,
                (Context) getContainer(), this, _statistics, _enabled );
        getContainer().getPipeline().addValve( _sessionTrackerValve );

        initNonStickyLockingMode( config );

        _transcoderService = createTranscoderService( _statistics );

        _upgradeSupportTranscoder = getTranscoderFactory().createSessionTranscoder( this );
        //容器启动时，初始化	_backupSessionService;
        _backupSessionService = new BackupSessionService( _transcoderService, _sessionBackupAsync, _sessionBackupTimeout,
                _backupThreadCount, _memcached, _nodeIdService, _statistics );

        _log.info( getClass().getSimpleName() + " finished initialization, have node ids " + config.getNodeIds() + " and failover node ids " + config.getFailoverNodeIds() );

    }

    /**
     * 将配置文件中的节点配置信息，区分正常与异常节点，并返回 配置信息对象  MemcachedConfig 
     * @param memcachedNodes	配置文件中的所有节点字符串
     * @param failoverNodes		配置文件中的错误节点字符串
     * @return
     */
    protected static MemcachedConfig createMemcachedConfig( final String memcachedNodes, final String failoverNodes ) {
        //至少一个节点
    	if ( !NODES_PATTERN.matcher( memcachedNodes ).matches() ) {
            throw new IllegalArgumentException( "Configured memcachedNodes attribute has wrong format, must match " + NODES_REGEX );
        }
        
        final List<String> nodeIds = new ArrayList<String>();
        final Matcher matcher = NODE_PATTERN.matcher( memcachedNodes  );
        final List<InetSocketAddress> addresses = new ArrayList<InetSocketAddress>();
        final Map<InetSocketAddress, String> address2Ids = new HashMap<InetSocketAddress, String>();
        while ( matcher.find() ) {
        	//该处只取了配置文件中的第一个节点
            initHandleNodeDefinitionMatch( matcher, addresses, address2Ids, nodeIds );
        }

        final List<String> failoverNodeIds = initFailoverNodes( failoverNodes, nodeIds );

        if ( nodeIds.isEmpty() ) {
            throw new IllegalArgumentException( "All nodes are also configured as failover nodes,"
                    + " this is a configuration failure. In this case, you probably want to leave out the failoverNodes." );
        }

        return new MemcachedConfig( memcachedNodes, failoverNodes, new NodeIdList( nodeIds ), failoverNodeIds, addresses, address2Ids );
    }

    /**
     * 获得序列化工具类
     * @param statistics
     * @return
     */
    private TranscoderService createTranscoderService( final Statistics statistics ) {
        return new TranscoderService( getTranscoderFactory().createTranscoder( this ) );
    }

    /**
     * 获得序列化工厂类
     * @return
     */
    protected TranscoderFactory getTranscoderFactory() {
        if ( _transcoderFactory == null ) {
            try {
                _transcoderFactory = createTranscoderFactory();
            } catch ( final Exception e ) {
                throw new RuntimeException( "Could not create transcoder factory.", e );
            }
        }
        return _transcoderFactory;
    }

    /**
     * 创建 MemcachedClient
     */
    protected MemcachedClient createMemcachedClient( final NodeIdList nodeIds, final List<InetSocketAddress> addresses,
            final Map<InetSocketAddress, String> address2Ids,
            final Statistics statistics ) {
    	//是否容许创建
        if ( ! _enabled.get() ) {
            return null;
        }
        try {
            final ConnectionFactory connectionFactory = createConnectionFactory( nodeIds, address2Ids, statistics );
            return new MemcachedClient( connectionFactory, addresses );
        } catch ( final Exception e ) {
            throw new RuntimeException( "Could not create memcached client", e );
        }
    }

    /**
     * 创建 MemcachedClient ConnectionFactory
     */
    private ConnectionFactory createConnectionFactory(
            final NodeIdList nodeIds, final Map<InetSocketAddress, String> address2Ids,
            final Statistics statistics ) {
        final MapBasedResolver resolver = new MapBasedResolver( address2Ids );
        if ( PROTOCOL_BINARY.equals( _memcachedProtocol ) ) {
        	// 本版不建议使用
            return new SuffixLocatorBinaryConnectionFactory( nodeIds, resolver, _sessionIdFormat, statistics );
        }
        return new SuffixLocatorConnectionFactory( nodeIds, resolver, _sessionIdFormat, statistics );
    }

    private TranscoderFactory createTranscoderFactory() throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        _log.info( "Creating transcoder factory " + _transcoderFactoryClassName );
        final Class<? extends TranscoderFactory> transcoderFactoryClass = loadTranscoderFactoryClass();
        final TranscoderFactory transcoderFactory = transcoderFactoryClass.newInstance();
        transcoderFactory.setCopyCollectionsForSerialization( _copyCollectionsForSerialization );
        if ( _customConverterClassNames != null ) {
            _log.info( "Found configured custom converter classes, setting on transcoder factory: " + _customConverterClassNames );
            transcoderFactory.setCustomConverterClassNames( _customConverterClassNames.split( ",\\s*" ) );
        }
        return transcoderFactory;
    }

    /**
     * 类加载器，加载序列化工厂类
     * @return
     * @throws ClassNotFoundException
     */
    private Class<? extends TranscoderFactory> loadTranscoderFactoryClass() throws ClassNotFoundException {
        Class<? extends TranscoderFactory> transcoderFactoryClass;
        final ClassLoader classLoader = getContainer().getLoader().getClassLoader();
        try {
            _log.debug( "Loading transcoder factory class " + _transcoderFactoryClassName + " using classloader " + classLoader );
            transcoderFactoryClass = Class.forName( _transcoderFactoryClassName, false, classLoader ).asSubclass( TranscoderFactory.class );
        } catch ( final ClassNotFoundException e ) {
            _log.info( "Could not load transcoderfactory class with classloader "+ classLoader +", trying " + getClass().getClassLoader() );
            transcoderFactoryClass = Class.forName( _transcoderFactoryClassName, false, getClass().getClassLoader() ).asSubclass( TranscoderFactory.class );
        }
        return transcoderFactoryClass;
    }

    /**
     * 
     * @param size				memcache 节点个数(包括可用和不可用)
     * @param ttlInMillis		在memcache中 存放时间
     * @param memcachedClient	memcachedClient
     * @return
     */
    protected NodeAvailabilityCache<String> createNodeAvailabilityCache( final int size, final long ttlInMillis,
            final MemcachedClient memcachedClient ) {
        return new NodeAvailabilityCache<String>( size, ttlInMillis, new CacheLoader<String>() {

            public boolean isNodeAvailable( final String  key) {
                try {
                	//_sessionIdFormat.createSessionId( "ping", key )  返回 "ping-" + memcachedId;
                    memcachedClient.get( _sessionIdFormat.createSessionId( "ping", key ) );
                    return true;
                } catch ( final Exception e ) {
                    return false;
                }
            }

        } );
    }

    /**
     * 从nodeIds中清除失效的memcached节点，并返回失效的节点
     * @param failoverNodes		失效的节点字符串
     * @param nodeIds			正常的节点
     * @return
     */
    private static List<String> initFailoverNodes( final String failoverNodes, final List<String> nodeIds ) {
        final List<String> failoverNodeIds = new ArrayList<String>();
        if ( failoverNodes != null && failoverNodes.trim().length() != 0 ) {
            final String[] failoverNodesArray = failoverNodes.split( " |," );
            for ( final String failoverNode : failoverNodesArray ) {
                final String nodeId = failoverNode.trim();
                if ( !nodeIds.remove( nodeId ) ) {
                    throw new IllegalArgumentException( "Invalid failover node id " + nodeId + ": "
                            + "not existing in memcachedNodes '" + nodeIds + "'." );
                }
                failoverNodeIds.add( nodeId );
            }
        }
        return failoverNodeIds;
    }

    /**
     * 将匹配的memcache节点：nodeId, host, port 都拆分开来，
     * 并放入响应的集合中该处只取了配置文件中的第一个节点
     * @param matcher		正则 对象
     * @param addresses		存放Inet<Inet>地址 集合
     * @param address2Ids	存放<Inet地址, 'n1'> 集合
     * @param nodeIds		存放nodeId<'n1'> 集合 
     */
    private static void initHandleNodeDefinitionMatch( final Matcher matcher, final List<InetSocketAddress> addresses,
            final Map<InetSocketAddress, String> address2Ids, final List<String> nodeIds ) {
        final String nodeId = matcher.group( 1 );
        nodeIds.add( nodeId );

        final String hostname = matcher.group( 2 );
        final int port = Integer.parseInt( matcher.group( 3 ) );
        final InetSocketAddress address = new InetSocketAddress( hostname, port );
        addresses.add( address );

        address2Ids.put( address, nodeId );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setContainer( final Container container ) {

        // De-register from the old Container (if any)
        if ( this.container != null && this.container instanceof Context ) {
            ( (Context) this.container ).removePropertyChangeListener( this );
        }

        // Default processing provided by our superclass
        super.setContainer( container );

        // Register with the new Container (if any)
        if ( this.container != null && this.container instanceof Context ) {
            setMaxInactiveInterval( ( (Context) this.container ).getSessionTimeout() * 60 );
            ( (Context) this.container ).addPropertyChangeListener( this );
        }

    }

    
    /**
     * 新的sessionId <b>sessionID-memcachedId(.clusterId)?</b>
     */
    @Override
    protected synchronized String generateSessionId() {
        return _sessionIdFormat.createSessionId( super.generateSessionId(), _nodeIdService.getMemcachedNodeId() );
    }

    /**
     * 销毁session
     */
    @Override
    public void expireSession( final String sessionId ) {
        if ( _log.isDebugEnabled() ) {
            _log.debug( "expireSession invoked: " + sessionId );
        }
        super.expireSession( sessionId );
        deleteFromMemcached( sessionId );
    }

    /**
     * Return the active Session, associated with this Manager, with the
     * specified session id (if any); otherwise return <code>null</code>.
     *
     * @param id
     *            The session id for the session to be returned
     * @return the session or <code>null</code> if no session was found locally
     *         or in memcached.
     *
     * @exception IllegalStateException
     *                if a new session cannot be instantiated for any reason
     * @exception IOException
     *                if an input/output error occurs while processing this
     *                request
     */
    @Override
    public Session findSession( final String id ) throws IOException {
    	// 内存中查找MemcachedBackupSession
        MemcachedBackupSession result = (MemcachedBackupSession) super.findSession( id );
        //内存不存在 && memcached 可用 && 忽略掉_missingSessionsCache不存在
        if ( result == null && canHitMemcached( id ) && _missingSessionsCache.get( id ) == null ) {
            // when the request comes from the container, it's from CoyoteAdapter.postParseRequest
            if ( !_sticky && _lockingStrategy.isContainerSessionLookup() ) {
                // we can return just null as the requestedSessionId will still be set on
                // the request.
                return null;
            }

            // else load the session from memcached
            result = loadFromMemcached( id );
            // checking valid() would expire() the session if it's not valid!
            if ( result != null && result.isValid() ) {

                // When the sessionId will be changed later in changeSessionIdOnTomcatFailover/handleSessionTakeOver
                // (due to a tomcat failover) we don't want to notify listeners via session.activate for the
                // old sessionId but do that later (in handleSessionTakeOver)
                // See also http://code.google.com/p/memcached-session-manager/issues/detail?id=92
                String jvmRoute;
                //判断session的tomcat实例和本地实例是否相同
                final boolean sessionIdWillBeChanged = _sticky && ( jvmRoute = getJvmRoute() ) != null
                    && !jvmRoute.equals( _sessionIdFormat.extractJvmRoute( id ) );

                final boolean activate = !sessionIdWillBeChanged;
                addValidLoadedSession( result, activate );
            }
        }
        return result;
    }
    
    /**
     * 将该session对象加入本地容器，并且是否要激活之
     * @param session
     * @param activate
     */
    private void addValidLoadedSession( final StandardSession session, final boolean activate ) {
        // make sure the listeners know about it. (as done by PersistentManagerBase)
        if ( session.isNew() ) {
            session.tellNew();
        }
        add( session );
        if ( activate ) {
            session.activate();
        }
        // endAccess() to ensure timeouts happen correctly.
        // access() to keep access count correct or it will end up
        // negative
        // 更新访问时间  
        session.access();
        session.endAccess();
    }

    /**
     * 创建一个session
     * 如果sessionid存在，而需要重新创建session，说明tomcat挂掉。。。其他
     * 从memcached中加载session对象，
     * 	如果从在，将session对象加载进本地容器，激活之
     * 	否则重新，重新创建指定sessionid的session对象
     */
    @Override
    public Session createSession( String sessionId ) {
        if ( _log.isDebugEnabled() ) {
            _log.debug( "createSession invoked: " + sessionId );
        }

        checkMaxActiveSessions();

        StandardSession session = null;

        if ( sessionId != null ) {
            session = loadFromMemcachedWithCheck( sessionId );
            // checking valid() would expire() the session if it's not valid!
            if ( session != null && session.isValid() ) {
                addValidLoadedSession( session, true );
            }
        }

        if ( session == null ) {

            session = createEmptySession();
            session.setNew( true );
            session.setValid( true );
            session.setCreationTime( System.currentTimeMillis() );
            session.setMaxInactiveInterval( this.maxInactiveInterval );
            //新建sessionID
            if ( sessionId == null || !isNodeAvailableForSessionId( sessionId ) ) {
                sessionId = generateSessionId();
            }

            session.setId( sessionId );

            if ( _log.isDebugEnabled() ) {
                _log.debug( "Created new session with id " + session.getId() );
            }

        }

        sessionCounter++;

        return session;

    }

    /**
     * 检查session并发是否达到上限
     */
    private void checkMaxActiveSessions() {
        if ( _maxActiveSessions >= 0 && sessions.size() >= _maxActiveSessions ) {
            _rejectedSessions++;
            throw new IllegalStateException
                (sm.getString("standardManager.createSession.ise"));
        }
    }
    
    /**
     * 检查sessionid的有效性
     * @param sessionId
     * @return
     */
    private boolean isNodeAvailableForSessionId( final String sessionId ) {
        final String nodeId = _sessionIdFormat.extractMemcachedId( sessionId );
        return nodeId != null && _nodeIdService.isNodeAvailable( nodeId );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MemcachedBackupSession createEmptySession() {
        final MemcachedBackupSession result = new MemcachedBackupSession( this );
        result.setSticky( _sticky );
        return result;
    }

    public void changeSessionId( final Session session ) {
        // e.g. invoked by the AuthenticatorBase (for BASIC auth) on login to prevent session fixation
        // so that session backup won't be omitted we must store this event
    	// FIXME 报错，故注释之
    	//        super.changeSessionId( session );
        ((MemcachedBackupSession)session).setSessionIdChanged( true );
    }

    /**
     * 
     * 
     * 
     * 
     * 
     * 
     */
    
   /**
     * <b>注：当 _sticky 为false，或者 未配置<Engine jvmRoute="tomcat1">该属性的时候，则 直接返回null; </b><br />
    * 如果 _sticky 为 false 直接返回null
    * 如果 _sticky 为 true
    * 	检查 sessionid中包含的JvmRoute 和 本地的 JvmRoute，如果相同为同一个容器，直接返回null
    * 	如果不是同一个容器则说明该请求为别的容器的session请求，
    * 	加载本地容器session，如果为空，则加载 memcached 中的session 重新将该session对象
    * 	加入本地容器中
    */
    @Override
    public String changeSessionIdOnTomcatFailover( final String requestedSessionId ) {
    	// 含义
        if ( !_sticky ) {
            return null;
        }
        // tomcat 实例名称，主要用在cluster中
        final String localJvmRoute = getJvmRoute();
        //单机测试为null;
        System.out.println("------>localJvmRoute:"+localJvmRoute);
        //本地tomcat 实例名 不为空， 并且 与sessionid里取出来的取出来 tomcat 实例名 不匹配。
        //说明没有启用cluster模式？
        if ( localJvmRoute != null && !localJvmRoute.equals( _sessionIdFormat.extractJvmRoute( requestedSessionId ) ) ) {

            // the session might have been loaded already (by some valve), so let's check our session map
        	// 是否已有session
        	//本地 sessions 取session
            MemcachedBackupSession session = (MemcachedBackupSession) sessions.get( requestedSessionId );
            if ( session == null ) {
            	//memcached 中取session
                session = loadFromMemcachedWithCheck( requestedSessionId );
            }

            // checking valid() can expire() the session!
            if ( session != null && session.isValid() ) {
                return handleSessionTakeOver( session );
            }
        }
        return null;
    }

    /**
     * 因sessionid 中 JvmRouteID的改变，所以改变本地容器session中sessionid的值，
     * 去掉旧session，（本地，memcached）加入新session，并激活。
     * @param session
     * @return
     */
    private String handleSessionTakeOver( final MemcachedBackupSession session ) {

        checkMaxActiveSessions();

        final String origSessionId = session.getIdInternal();

        final String newSessionId = _sessionIdFormat.changeJvmRoute( session.getIdInternal(), getJvmRoute() );

        // If this session was already loaded we need to remove it from the session map
        // See http://code.google.com/p/memcached-session-manager/issues/detail?id=92
        if ( sessions.containsKey( origSessionId ) ) {
            sessions.remove( origSessionId );
        }

        session.setIdInternal( newSessionId );

        addValidLoadedSession( session, true );

        deleteFromMemcached( origSessionId );

        _statistics.requestWithTomcatFailover();

        return newSessionId;

    }

    /**
     * 清除 memcache session
     * @param sessionId
     */
    protected void deleteFromMemcached(final String sessionId) {
        if ( _enabled.get() && _sessionIdFormat.isValid( sessionId ) ) {
            if ( _log.isDebugEnabled() ) {
                _log.debug( "Deleting session from memcached: " + sessionId );
            }
            try {
                final long start = System.currentTimeMillis();
                _memcached.delete( sessionId );
                _statistics.registerSince( DELETE_FROM_MEMCACHED, start );
                if ( !_sticky ) {
                    _lockingStrategy.onAfterDeleteFromMemcached( sessionId );
                }
            } catch ( final Throwable e ) {
                _log.info( "Could not delete session from memcached.", e );
            }
        }
    }

    /**
     * 如果 _sticky 为true 则从本地容器查找session，验证session的有效性isValid()，
     * 		验证session的nodeid是否可用（如不可用将查找可用的新nodeid），封装新的sessionid，并返回封装后的sessionid串
     * 如果 _sticky 为false则从memcached加载session的备份信息，需验证 备份的有效性	session验证信息是否有效，
     * 		验证都通过的情况下，将新加载的备份session重新加入本地容器中。并返回封装后的sessionid串
     */
    @Override
    public String changeSessionIdOnMemcachedFailover( final String requestedSessionId ) {

        try {
        	//本地容器加载session
        	//nodeID 有效性验证 session中的nodeID 不可用，就换个可用的
            if ( _sticky ) {
                /* We can just lookup the session in the local session map, as we wouldn't get
                 * the session from memcached if the node was not available - or, the other way round,
                 * if we would get the session from memcached, the session would not have to be relocated.
                 */
                final MemcachedBackupSession session = (MemcachedBackupSession) super.findSession( requestedSessionId );

                if ( session != null && session.isValid() ) {
                    final String nodeId = _sessionIdFormat.extractMemcachedId( session.getId() );
                    final String newNodeId = getNewNodeIdIfUnavailable( nodeId );
                    if ( newNodeId != null ) {
                        final String newSessionId = _sessionIdFormat.createNewSessionId( session.getId(), newNodeId );
                        _log.debug( "Session needs to be relocated, setting new id on session..." );
                        session.setIdForRelocate( newSessionId );
                        _statistics.requestWithMemcachedFailover();
                        return newSessionId;
                    }
                }
            }
            //memcached 加载session
            else {
            	//memcached 中加载 ，并且激活session
                /* for non-sticky sessions we check the validity info
                 */
                final String nodeId = _sessionIdFormat.extractMemcachedId( requestedSessionId );
                if ( nodeId == null || _nodeIdService.isNodeAvailable( nodeId ) ) {
                    return null;
                }

                _log.info( "Session needs to be relocated as node "+ nodeId +" is not available, loading backup session for " + requestedSessionId );
                final MemcachedBackupSession backupSession = loadBackupSession( requestedSessionId );
                if ( backupSession != null ) {
                    _log.debug( "Loaded backup session for " + requestedSessionId + ", adding locally with "+ backupSession.getIdInternal() +"." );
                    addValidLoadedSession( backupSession, true );
                    _statistics.requestWithMemcachedFailover();
                    return backupSession.getId();
                }
            }

        } catch ( final IOException e ) {
            _log.warn( "Could not find session in local session map.", e );
        }
        return null;
    }

    /**
     * 如果一下出现无效的情况，直接返回null,
     * 首先验证 NodeId可用性，
     * 其次验证的备份的有效性验证信息("bak:" + "validity:" + sessionid)是否有效
     * 然后反序列化备份的session对象（"bak:" + sessionId）
     * 更新反序列化备份的session对象时间戳属性
     * 并且返回 session对象
     * @param requestedSessionId
     * @return
     */
 // FIXME
    @CheckForNull
    private MemcachedBackupSession loadBackupSession( @Nonnull final String requestedSessionId ) {
        final String backupNodeId = getBackupNodeId( requestedSessionId );
        //验证nodeid 不为空
        if ( backupNodeId == null ) {
            _log.info( "No backup node found for nodeId "+ _sessionIdFormat.extractMemcachedId( requestedSessionId ) );
            return null;
        }
        
        //节点不可用执行
        if ( !_nodeIdService.isNodeAvailable( backupNodeId ) ) {
            _log.info( "Node "+ backupNodeId +" that stores the backup of the session "+ requestedSessionId +" is not available." );
            return null;
        }

        try {
        	//session有效性信息
            final SessionValidityInfo validityInfo = _lockingStrategy.loadBackupSessionValidityInfo( requestedSessionId );
            if ( validityInfo == null || !validityInfo.isValid() ) {
                _log.info( "No validity info (or no valid one) found for sessionId " + requestedSessionId );
                return null;
            }
            //查找 session 备份信息 （"bak:" + sessionid）
            final Object obj = _memcached.get( _sessionIdFormat.createBackupKey( requestedSessionId ) );
            if ( obj == null ) {
                _log.info( "No backup found for sessionId " + requestedSessionId );
                return null;
            }

            final MemcachedBackupSession session = _transcoderService.deserialize( (byte[]) obj, getContainer().getRealm(), this );
            session.setSticky( _sticky );
            session.setLastAccessedTimeInternal( validityInfo.getLastAccessedTime() );
            session.setThisAccessedTimeInternal( validityInfo.getThisAccessedTime() );

            final String newSessionId = _sessionIdFormat.createNewSessionId( requestedSessionId, backupNodeId );
            _log.info( "Session backup loaded from secondary memcached for "+ requestedSessionId +" (will be relocated)," +
            		" setting new id "+ newSessionId +" on session..." );
            session.setIdInternal( newSessionId );
            return session;

        } catch( final Exception e ) {
            _log.error( "Could not get backup validityInfo or backup session for sessionId " + requestedSessionId, e );
        }
        return null;
    }

    /**
     * Determines if the (secondary) memcached node used for failover backup of non-sticky sessions is available.
     * @param sessionId the id of the session that shall be stored in another, secondary memcached node.
     * @return <code>true</code> if the backup node is available. If there's no secondary memcached node
     *         (e.g. as there's only a single memcached), <code>false</code> is returned.
     * @see #getBackupNodeId(String)
     */
    boolean isBackupNodeAvailable( @Nonnull final String sessionId ) {
        final String backupNodeId = getBackupNodeId( sessionId );
        return backupNodeId == null ? false : _nodeIdService.isNodeAvailable( backupNodeId );
    }

    /**
     * 根据sessionid 提取一个 nodeid 如果不存在，则从 nodeList里重新找一个
     * @param sessionId
     * @return
     */
    @CheckForNull
    String getBackupNodeId( @Nonnull final String sessionId ) {
        final String nodeId = _sessionIdFormat.extractMemcachedId( sessionId );
        return nodeId == null ? null : _nodeIdService.getNextNodeId( nodeId );
    }

    /**
     * 如果该nodeid无效，则获得一个有效的nodeid
     * Returns a new node id if the given one is <code>null</code> or not available.
     * @param nodeId the node id that is checked for availability (if not <code>null</code>).
     * @return a new node id if the given one is <code>null</code> or not available, otherwise <code>null</code>.
     */
    private String getNewNodeIdIfUnavailable( final String nodeId ) {
        final String newNodeId;
        if ( nodeId == null ) {
            newNodeId = _nodeIdService.getMemcachedNodeId();
        }
        else {
            if ( !_nodeIdService.isNodeAvailable( nodeId ) ) {
                newNodeId = _nodeIdService.getAvailableNodeId( nodeId );
                if ( newNodeId == null ) {
                    _log.warn( "The node " + nodeId + " is not available and there's no node for relocation left." );
                }
            }
            else {
                newNodeId = null;
            }
        }
        return newNodeId;
    }

    /**
     * 此方法 供 SessionTrackerValve 中调用
     * 检查 _enabled 是否开启，开启继续执行
     * 检查容器中是否包含session	包含继续执行
     * 检查session是否有效     有效继续执行
     * 传递的 sessionid 有变 或者 _sticky 为false 并且未超时  继续执行
     * 	验证sessionid中nodeid
	 *  验证上次备份的时候和当前访问的时间是否相同
	 *  验证session.attributes 未被访问
	 *  验证sessionid 是否有变，session是否过期，
	 *  验证权限信息是否变化
	 *  验证是否为新session 只有创建sessionid时才为true
	 *  当session的attributes发生变化，或者 _force 为true 或者权限信息发生变化时，
	 *  都满足时才执行session更新操作
     * Store the provided session in memcached if the session was modified
     * or if the session needs to be relocated.
     *
     * @param sessionId
     *            the id of the session to save
     * @param sessionRelocationRequired
     *            specifies, if the session id was changed due to a memcached failover or tomcat failover.
     * @param requestId
     *            the uri/id of the request for that the session backup shall be performed, used for readonly tracking.
     * @return the {@link SessionTrackerValve.SessionBackupService.BackupResultStatus}
     */
    public Future<BackupResult> backupSession( final String sessionId, final boolean sessionIdChanged, final String requestId ) {
        //没有开启memcached
    	if ( !_enabled.get() ) {
            return new SimpleFuture<BackupResult>( BackupResult.SKIPPED );
        }
        
        //容器 中取 msmSession 
        final MemcachedBackupSession msmSession = (MemcachedBackupSession) sessions.get( sessionId );
        if ( msmSession == null ) {
        	System.out.println("------------------->backupSession-->msmSession == null");
            _log.debug( "No session found in session map for " + sessionId );
            if ( !_sticky ) {
            	//更新有效性验证信息，和备份的有效性验证信息
                _lockingStrategy.onBackupWithoutLoadedSession( sessionId, requestId, _backupSessionService );
            }
            return new SimpleFuture<BackupResult>( BackupResult.SKIPPED );
        }
        //session 无效
        if ( !msmSession.isValidInternal() ) {
            _log.debug( "Non valid session found in session map for " + sessionId );
            return new SimpleFuture<BackupResult>( BackupResult.SKIPPED );
        }
        
        //钝化 session ？ 或者理解为生效新添加的session属性？
        if ( !_sticky ) {
            msmSession.passivate();
        }
        //传递的 sessionIdChanged || msmSession sessionIdChanged || _sticky 为false && session未超时
        final boolean force = sessionIdChanged || msmSession.isSessionIdChanged() || !_sticky && (msmSession.getSecondsSinceLastBackup() >= msmSession.getMaxInactiveInterval());
        final Future<BackupResult> result = _backupSessionService.backupSession( msmSession, force );

        if ( !_sticky ) {
            remove( msmSession, false );
            _lockingStrategy.onAfterBackupSession( msmSession, force, result, requestId, _backupSessionService );
        }

        return result;
    }
    
    /**
     * 	序列化	MemcachedBackupSession
     * @param session
     * @return
     */
    @Nonnull
    byte[] serialize( @Nonnull final MemcachedBackupSession session ) {
        return _transcoderService.serialize( session );
    }

    /**
     * 首先验证 sessionId 是否有效，在验证 未命中的sessionid缓存中是否存在；
     * 否则去 memcached 去查找 该 MemcachedBackupSession
     * @param sessionId
     * @return
     */
    protected MemcachedBackupSession loadFromMemcachedWithCheck( final String sessionId ) {
//    	_missingSessionsCache.get( sessionId ) != null 说明该sessionid在memcached中不存在
        if ( !canHitMemcached( sessionId ) || _missingSessionsCache.get( sessionId ) != null ) {
            return null;
        }
        return loadFromMemcached( sessionId );
    }

    /**
     * 测试 memcache功能是否开启，sessionid是否符合规则 <br/>
     * Checks if this manager {@link #isEnabled()}, if the given sessionId is valid (contains a memcached id)
     * and if this sessionId is not in our missingSessionsCache.
     */
    private boolean canHitMemcached( @Nonnull final String sessionId ) {
        return _enabled.get() && _sessionIdFormat.isValid( sessionId );
    }

    /**
     * 根据sessionId，从Memcached 去取MemcachedBackupSession
     * 首先执行锁定操作
     * 	如果存在 ，并加载session的有效性验证信息，并返回之
     *  如果不存在，则在储存未命中的sessionid，释放锁，返回null; <br/>
     * Assumes that before you checked {@link #canHitMemcached(String)}.
     */
 // FIXME
    private MemcachedBackupSession loadFromMemcached( final String sessionId ) {
        final String nodeId = _sessionIdFormat.extractMemcachedId( sessionId );
        if ( nodeId == null ) {
            throw new IllegalArgumentException( "The sessionId should contain a nodeId, this should be checked" +
            		" by invoking canHitMemcached before invoking this method (bug, needs fix)." );
        }
        // nodeid 失效
        if ( !_nodeIdService.isNodeAvailable( nodeId ) ) {
            _log.debug( "Asked for session " + sessionId + ", but the related"
                    + " memcached node is still marked as unavailable (won't load from memcached)." );
        // nodeid 有效
        } else {
        	
            if ( _log.isDebugEnabled() ) {
                _log.debug( "Loading session from memcached: " + sessionId );
            }

            LockStatus lockStatus = null;
            try {

                if ( !_sticky ) {
                	//锁定操作
                    lockStatus = _lockingStrategy.onBeforeLoadFromMemcached( sessionId );
                }

                final long start = System.currentTimeMillis();

                /* In the previous version (<1.2) the session was completely serialized by
                 * custom Transcoder implementations.
                 * Such sessions have set the SERIALIZED flag (from SerializingTranscoder) so that
                 * they get deserialized by BaseSerializingTranscoder.deserialize or the appropriate
                 * specializations.
                 */
                // 取出session对象
                final Object object = _memcached.get( sessionId, _upgradeSupportTranscoder );
                _nodeIdService.setNodeAvailable( nodeId, true );

                if ( object != null ) {
                    final MemcachedBackupSession result;
                    
                    // 转MemcachedBackupSession对象
                    if ( object instanceof MemcachedBackupSession ) {
                        result = (MemcachedBackupSession) object;
                    }
                    else {
                        final long startDeserialization = System.currentTimeMillis();
                        result = _transcoderService.deserialize( (byte[]) object, getContainer().getRealm(), this );
                        _statistics.registerSince( SESSION_DESERIALIZATION, startDeserialization );
                    }
                    _statistics.registerSince( LOAD_FROM_MEMCACHED, start );

                    result.setSticky( _sticky );
                    if ( !_sticky ) {
                    	//加载session有效性信息
                        _lockingStrategy.onAfterLoadFromMemcached( result, lockStatus );
                    }

                    if ( _log.isDebugEnabled() ) {
                        _log.debug( "Found session with id " + sessionId );
                    }
                    return result;
                }
                else {
                	//释放锁
                    if ( lockStatus == LockStatus.LOCKED ) {
                        _lockingStrategy.releaseLock( sessionId );
                    }
                    _missingSessionsCache.put( sessionId, Boolean.TRUE );
                    if ( _log.isDebugEnabled() ) {
                        _log.debug( "Session " + sessionId + " not found in memcached." );
                    }
                    return null;
                }

            } catch ( final NodeFailureException e ) {
                _log.warn( "Could not load session with id " + sessionId + " from memcached." );
                _nodeIdService.setNodeAvailable( nodeId, false );
            } catch ( final Exception e ) {
                _log.warn( "Could not load session with id " + sessionId + " from memcached.", e );
                if ( lockStatus == LockStatus.LOCKED ) {
                    _lockingStrategy.releaseLock( sessionId );
                }
            }
        }
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void remove( final Session session ) {
        remove( session, session.getNote( NODE_FAILURE ) != Boolean.TRUE );
    }

    /**
     * 清除 msm session  tomcat session
     * @param session				session
     * @param removeFromMemcached	是否清除memcache session
     */
    private void remove( final Session session, final boolean removeFromMemcached ) {
        if ( _log.isDebugEnabled() ) {
            _log.debug( "remove invoked, removeFromMemcached: " + removeFromMemcached +
                    ", id: " + session.getId() );
        }
        if ( removeFromMemcached ) {
            deleteFromMemcached( session.getId() );
        }
        super.remove( session );
    }

    /**
     * Set the maximum number of active Sessions allowed, or -1 for no limit.
     *
     * @param max
     *            The new maximum number of sessions
     */
    public void setMaxActiveSessions( final int max ) {
        final int oldMaxActiveSessions = _maxActiveSessions;
        _maxActiveSessions = max;
        support.firePropertyChange( "maxActiveSessions",
                Integer.valueOf( oldMaxActiveSessions ),
                Integer.valueOf( _maxActiveSessions ) );
    }

    /**
     * {@inheritDoc}
     */
    public int getRejectedSessions() {
        return _rejectedSessions;
    }

    /**
     * {@inheritDoc}
     */
    public void load() throws ClassNotFoundException, IOException {
    }

    /**
     * {@inheritDoc}
     */
    public void setRejectedSessions( final int rejectedSessions ) {
        _rejectedSessions = rejectedSessions;
    }

    /**
     * {@inheritDoc}
     */
    public void unload() throws IOException {
    }

    /**
     * Set the memcached nodes space or comma separated.
     * <p>
     * E.g. <code>n1.localhost:11211 n2.localhost:11212</code>
     * </p>
     * <p>
     * When the memcached nodes are set when this manager is already initialized,
     * the new configuration will be loaded.
     * </p>
     *
     * @param memcachedNodes
     *            the memcached node definitions, whitespace or comma separated
     */
    public void setMemcachedNodes( final String memcachedNodes ) {
        if ( initialized ) {
            final MemcachedConfig config = reloadMemcachedConfig( memcachedNodes, _failoverNodes );
            _log.info( "Loaded new memcached node configuration." +
                    "\n- Former config: "+ _memcachedNodes +
                    "\n- New config: " + config.getMemcachedNodes() +
                    "\n- New node ids: " + config.getNodeIds() +
                    "\n- New failover node ids: " + config.getFailoverNodeIds() );
        }
        _memcachedNodes = memcachedNodes;
    }

    /**
     * The memcached nodes configuration as provided in the server.xml/context.xml.
     * <p>
     * This getter is there to make this configuration accessible via jmx.
     * </p>
     * @return the configuration string for the memcached nodes.
     */
    public String getMemcachedNodes() {
        return _memcachedNodes;
    }

    /**
     * 重载 MemcachedConfig
     * @param memcachedNodes
     * @param failoverNodes
     * @return
     */
    private MemcachedConfig reloadMemcachedConfig( final String memcachedNodes, final String failoverNodes ) {

        /* first create all dependent services
         */
        final MemcachedConfig config = createMemcachedConfig( memcachedNodes, failoverNodes );
        final MemcachedClient memcachedClient = createMemcachedClient( config.getNodeIds(), config.getAddresses(),
                config.getAddress2Ids(), _statistics );
        final NodeIdService nodeIdService = new NodeIdService(
                createNodeAvailabilityCache( config.getCountNodes(), NODE_AVAILABILITY_CACHE_TTL, memcachedClient ),
                config.getNodeIds(), config.getFailoverNodeIds() );
        final BackupSessionService backupSessionService = new BackupSessionService( _transcoderService, _sessionBackupAsync,
                _sessionBackupTimeout, _backupThreadCount, memcachedClient, nodeIdService, _statistics );

        /* then assign new services
         */
        if ( _memcached != null ) {
            _memcached.shutdown();
        }
        _memcached = memcachedClient;
        _nodeIdService = nodeIdService;
        _backupSessionService = backupSessionService;

        initNonStickyLockingMode( config );

        return config;
    }

    /**
     * The node ids of memcached nodes, that shall only be used for session
     * backup by this tomcat/manager, if there are no other memcached nodes
     * left. Node ids are separated by whitespace or comma.
     * <p>
     * E.g. <code>n1 n2</code>
     * </p>
     * <p>
     * When the failover nodes are set when this manager is already initialized,
     * the new configuration will be loaded.
     * </p>
     *
     * @param failoverNodes
     *            the failoverNodes to set, whitespace or comma separated
     */
    public void setFailoverNodes( final String failoverNodes ) {
        if ( initialized ) {
            final MemcachedConfig config = reloadMemcachedConfig( _memcachedNodes, failoverNodes );
            _log.info( "Loaded new memcached failover node configuration." +
                    "\n- Former failover config: "+ _failoverNodes +
                    "\n- New failover config: " + config.getFailoverNodes() +
                    "\n- New node ids: " + config.getNodeIds() +
                    "\n- New failover node ids: " + config.getFailoverNodeIds() );
        }
        _failoverNodes = failoverNodes;
    }

    /**
     * The memcached failover nodes configuration as provided in the server.xml/context.xml.
     * <p>
     * This getter is there to make this configuration accessible via jmx.
     * </p>
     * @return the configuration string for the failover nodes.
     */
    public String getFailoverNodes() {
        return _failoverNodes;
    }

    /**
     * Set the regular expression for request uris to ignore for session backup.
     * This should include static resources like images, in the case they are
     * served by tomcat.
     * <p>
     * E.g. <code>.*\.(png|gif|jpg|css|js)$</code>
     * </p>
     *
     * @param requestUriIgnorePattern
     *            the requestUriIgnorePattern to set
     * @author Martin Grotzke
     */
    public void setRequestUriIgnorePattern( final String requestUriIgnorePattern ) {
        _requestUriIgnorePattern = requestUriIgnorePattern;
    }

    /**
     * The class of the factory that creates the
     * {@link net.spy.memcached.transcoders.Transcoder} to use for serializing/deserializing
     * sessions to/from memcached (requires a default/no-args constructor).
     * The default value is the {@link JavaSerializationTranscoderFactory} class
     * (used if this configuration attribute is not specified).
     * <p>
     * After the {@link TranscoderFactory} instance was created from the specified class,
     * {@link TranscoderFactory#setCopyCollectionsForSerialization(boolean)}
     * will be invoked with the currently set <code>copyCollectionsForSerialization</code> propery, which
     * has either still the default value (<code>false</code>) or the value provided via
     * {@link #setCopyCollectionsForSerialization(boolean)}.
     * </p>
     *
     * @param transcoderFactoryClassName the {@link TranscoderFactory} class name.
     */
    public void setTranscoderFactoryClass( final String transcoderFactoryClassName ) {
        _transcoderFactoryClassName = transcoderFactoryClassName;
    }

    /**
     * Specifies, if iterating over collection elements shall be done on a copy
     * of the collection or on the collection itself. The default value is <code>false</code>
     * (used if this configuration attribute is not specified).
     * <p>
     * This option can be useful if you have multiple requests running in
     * parallel for the same session (e.g. AJAX) and you are using
     * non-thread-safe collections (e.g. {@link java.util.ArrayList} or
     * {@link java.util.HashMap}). In this case, your application might modify a
     * collection while it's being serialized for backup in memcached.
     * </p>
     * <p>
     * <strong>Note:</strong> This must be supported by the {@link TranscoderFactory}
     * specified via {@link #setTranscoderFactoryClass(String)}: after the {@link TranscoderFactory} instance
     * was created from the specified class, {@link TranscoderFactory#setCopyCollectionsForSerialization(boolean)}
     * will be invoked with the provided <code>copyCollectionsForSerialization</code> value.
     * </p>
     *
     * @param copyCollectionsForSerialization
     *            <code>true</code>, if iterating over collection elements shall be done
     *            on a copy of the collection, <code>false</code> if the collections own iterator
     *            shall be used.
     */
    public void setCopyCollectionsForSerialization( final boolean copyCollectionsForSerialization ) {
        _copyCollectionsForSerialization = copyCollectionsForSerialization;
    }

    /**
     * Custom converter allow you to provide custom serialization of application specific
     * types. Multiple converter classes are separated by comma (with optional space following the comma).
     * <p>
     * This option is useful if reflection based serialization is very verbose and you want
     * to provide a more efficient serialization for a specific type.
     * </p>
     * <p>
     * <strong>Note:</strong> This must be supported by the {@link TranscoderFactory}
     * specified via {@link #setTranscoderFactoryClass(String)}: after the {@link TranscoderFactory} instance
     * was created from the specified class, {@link TranscoderFactory#setCustomConverterClassNames(String[])}
     * is invoked with the provided custom converter class names.
     * </p>
     * <p>Requirements regarding the specific custom converter classes depend on the
     * actual serialization strategy, but a common requirement would be that they must
     * provide a default/no-args constructor.<br/>
     * For more details have a look at
     * <a href="http://code.google.com/p/memcached-session-manager/wiki/SerializationStrategies">SerializationStrategies</a>.
     * </p>
     *
     * @param customConverterClassNames a list of class names separated by comma
     */
    public void setCustomConverter( final String customConverterClassNames ) {
        _customConverterClassNames = customConverterClassNames;
    }

    /**
     * Specifies if statistics (like number of requests with/without session) shall be
     * gathered. Default value of this property is <code>true</code>.
     * <p>
     * Statistics will be available via jmx and the Manager mbean (
     * e.g. in the jconsole mbean tab open the attributes node of the
     * <em>Catalina/Manager/&lt;context-path&gt;/&lt;host name&gt;</em>
     * mbean and check for <em>msmStat*</em> values.
     * </p>
     *
     * @param enableStatistics <code>true</code> if statistics shall be gathered.
     */
    public void setEnableStatistics( final boolean enableStatistics ) {
        final boolean oldEnableStatistics = _enableStatistics;
        _enableStatistics = enableStatistics;
        if ( oldEnableStatistics != enableStatistics && initialized ) {
            _log.info( "Changed enableStatistics from " + oldEnableStatistics + " to " + enableStatistics + "." +
            " Reloading configuration..." );
            reloadMemcachedConfig( _memcachedNodes, _failoverNodes );
        }
    }

    /**
     * Specifies the number of threads that are used if {@link #setSessionBackupAsync(boolean)}
     * is set to <code>true</code>.
     *
     * @param backupThreadCount the number of threads to use for session backup.
     */
    public void setBackupThreadCount( final int backupThreadCount ) {
        final int oldBackupThreadCount = _backupThreadCount;
        _backupThreadCount = backupThreadCount;
        if ( initialized ) {
            _log.info( "Changed backupThreadCount from " + oldBackupThreadCount + " to " + _backupThreadCount + "." +
                    " Reloading configuration..." );
            reloadMemcachedConfig( _memcachedNodes, _failoverNodes );
            _log.info( "Finished reloading configuration." );
        }
    }

    /**
     * The number of threads to use for session backup if session backup shall be
     * done asynchronously.
     * @return the number of threads for session backup.
     */
    public int getBackupThreadCount() {
        return _backupThreadCount;
    }

    /**
     * Specifies the memcached protocol to use, either "text" (default) or "binary".
     *
     * @param memcachedProtocol one of "text" or "binary".
     */
    public void setMemcachedProtocol( final String memcachedProtocol ) {
        if ( !PROTOCOL_TEXT.equals( memcachedProtocol )
                && !PROTOCOL_BINARY.equals( memcachedProtocol ) ) {
            _log.warn( "Illegal memcachedProtocol " + memcachedProtocol + ", using default (" + _memcachedProtocol + ")." );
            return;
        }
        _memcachedProtocol = memcachedProtocol;
    }

    /**
     * Enable/disable memcached-session-manager (default <code>true</code> / enabled).
     * If disabled, sessions are neither looked up in memcached nor stored in memcached.
     *
     * @param enabled specifies if msm shall be disabled or not.
     * @throws IllegalStateException it's not allowed to disable this session manager when running in non-sticky mode.
     */
    public void setEnabled( final boolean enabled ) throws IllegalStateException {
        if ( !enabled && !_sticky ) {
            throw new IllegalStateException( "Disabling this session manager is not allowed in non-sticky mode. You must switch to sticky operation mode before." );
        }
        final boolean changed = _enabled.compareAndSet( !enabled, enabled );
        if ( changed && initialized ) {
            reloadMemcachedConfig( _memcachedNodes, _failoverNodes );
            _log.info( "Changed enabled status to " + enabled + "." );
        }
    }

    /**
     * Specifies, if msm is enabled or not.
     *
     * @return <code>true</code> if enabled, otherwise <code>false</code>.
     */
    public boolean isEnabled() {
        return _enabled.get();
    }

    public void setSticky( final boolean sticky ) {
        if ( sticky == _sticky ) {
            return;
        }
        if ( !sticky && getJvmRoute() != null ) {
            _log.warn( "Setting sticky to false while there's still a jvmRoute configured (" + getJvmRoute() + "), this might cause trouble." +
            		" You should remve the jvmRoute configuration for non-sticky mode." );
        }
        _sticky = sticky;
        if ( initialized ) {
            _log.info( "Changed sticky to " + _sticky + ". Reloading configuration..." );
            reloadMemcachedConfig( _memcachedNodes, _failoverNodes );
            _log.info( "Finished reloading configuration." );
        }
    }
    
    protected void setStickyInternal( final boolean sticky ) {
        _sticky = sticky;
    }

    public boolean isSticky() {
        return _sticky;
    }

    /**
     * Sets the session locking mode. Possible values:
     * <ul>
     * <li><code>none</code> - does not lock the session at all (default for non-sticky sessions).</li>
     * <li><code>all</code> - the session is locked for each request accessing the session.</li>
     * <li><code>auto</code> - locks the session for each request except for those the were detected to access the session only readonly.</li>
     * <li><code>uriPattern:&lt;regexp&gt;</code> - locks the session for each request with a request uri (with appended querystring) matching
     * the provided regular expression.</li>
     * </ul>
     */
    public void setLockingMode( @Nullable final String lockingMode ) {
        if ( lockingMode == null && _lockingMode == null
                || lockingMode != null && lockingMode.equals( _lockingMode ) ) {
            return;
        }
        _lockingMode = lockingMode;
        if ( initialized ) {
            initNonStickyLockingMode( createMemcachedConfig( _memcachedNodes, _failoverNodes ) );
        }
    }

    private void initNonStickyLockingMode( @Nonnull final MemcachedConfig config ) {
        if ( _sticky ) {
            setLockingMode( null, null, false );
            return;
        }

        Pattern uriPattern = null;
        LockingMode lockingMode = null;
        if ( _lockingMode != null ) {
            if ( _lockingMode.startsWith( "uriPattern:" ) ) {
                lockingMode = LockingMode.URI_PATTERN;
                uriPattern = Pattern.compile( _lockingMode.substring( "uriPattern:".length() ) );
            }
            else {
                lockingMode = LockingMode.valueOf( _lockingMode.toUpperCase() );
            }
        }
        if ( lockingMode == null ) {
            lockingMode = LockingMode.NONE;
        }
        final boolean storeSecondaryBackup = config.getCountNodes() > 1;
        setLockingMode( lockingMode, uriPattern, storeSecondaryBackup );
    }

    public void setLockingMode( @Nullable final LockingMode lockingMode, @Nullable final Pattern uriPattern, final boolean storeSecondaryBackup ) {
        _log.info( "Setting lockingMode to " + lockingMode + ( uriPattern != null ? " with pattern " + uriPattern.pattern() : "" ) );
        _lockingStrategy = LockingStrategy.create( lockingMode, uriPattern, _memcached, this, _missingSessionsCache, storeSecondaryBackup, _statistics );
        if ( _sessionTrackerValve != null ) {
            _sessionTrackerValve.setLockingStrategy( _lockingStrategy );
        }
    }

    /**
     * Lifecycle 实现方法
     */
    public void addLifecycleListener( final LifecycleListener arg0 ) {
        _lifecycle.addLifecycleListener( arg0 );
    }

    /**
     * Lifecycle 实现方法
     */
    public LifecycleListener[] findLifecycleListeners() {
        return _lifecycle.findLifecycleListeners();
    }

    /**
     * Lifecycle 实现方法
     */
    public void removeLifecycleListener( final LifecycleListener arg0 ) {
        _lifecycle.removeLifecycleListener( arg0 );
    }

    /**
     * 容器启动执行
     * Lifecycle 实现方法
     */
    public void start() throws LifecycleException {

        if( ! initialized ) {
            init();
        }

        // Validate and update our current component state
        if (_started) {
            return;
        }
        _lifecycle.fireLifecycleEvent(START_EVENT, null);
        _started = true;

        // Force initialization of the random number generator
        if (log.isDebugEnabled()) {
            log.debug("Force random number initialization starting");
        }
        super.generateSessionId();
        if (log.isDebugEnabled()) {
            log.debug("Force random number initialization completed");
        }

        startInternal( null );
    }

    /**
     * 关闭执行
     * Lifecycle 实现方法
     */
    public void stop() throws LifecycleException {

        if (log.isDebugEnabled()) {
            log.debug("Stopping");
        }

        // Validate and update our current component state
        if (!_started) {
            throw new LifecycleException
                (sm.getString("standardManager.notStarted"));
        }
        _lifecycle.fireLifecycleEvent(STOP_EVENT, null);
        _started = false;

        // Require a new random number generator if we are restarted
        random = null;

        if ( initialized ) {

            if ( _sticky ) {
                _log.info( "Removing sessions from local session map." );
                for( final Session session : sessions.values() ) {
                    swapOut( (StandardSession) session );
                }
            }

            _log.info( "Stopping services." );
            _backupSessionService.shutdown();
            if ( _lockingStrategy != null ) {
                _lockingStrategy.shutdown();
            }
            if ( _memcached != null ) {
                _memcached.shutdown();
            }

            destroy();
        }
    }

    /**
     * 清理缓冲区 ？
     * @param session
     */
    private void swapOut( @Nonnull final StandardSession session ) {
        // implementation like the one in PersistentManagerBase.swapOut
        if (!session.isValid()) {
            return;
        }
        session.passivate();
        remove( session, false );
        session.recycle();
    }

    /**
     * org.apache.catalina.core.StandardContext.backgroundProcess())该方法中调用之，后台程序
     * 后台程序定时检查。。。时间戳和会话对象是否超期
     */
    @Override
    public void backgroundProcess() {
        updateExpirationInMemcached();
        super.backgroundProcess();
    }
    
    /**
     * session 会话超时检查
     */
    protected void updateExpirationInMemcached() {
    	System.out.println("------------->updateExpirationInMemcached()");
        if ( _enabled.get() && _sticky ) {
            final Session[] sessions = findSessions();
            final int delay = getContainer().getBackgroundProcessorDelay();
            for ( final Session s : sessions ) {
                final MemcachedBackupSession session = (MemcachedBackupSession) s;
                if ( _log.isDebugEnabled() ) {
                    _log.debug( "Checking session " + session.getId() + ": " +
                            "\n- isValid: " + session.isValidInternal() +
                            "\n- isExpiring: " + session.isExpiring() +
                            "\n- isBackupRunning: " + session.isBackupRunning() +
                            "\n- isExpirationUpdateRunning: " + session.isExpirationUpdateRunning() +
                            "\n- wasAccessedSinceLastBackup: " + session.wasAccessedSinceLastBackup() +
                            "\n- memcachedExpirationTime: " + session.getMemcachedExpirationTime() );
                }
                //session有效的情况下才做更新操作
                if ( session.isValidInternal()
                        && !session.isExpiring()
                        && !session.isBackupRunning()
                        && !session.isExpirationUpdateRunning()
                        && session.wasAccessedSinceLastBackup()
                        && session.getMaxInactiveInterval() > 0 // for <= 0 the session was stored in memcached with expiration 0
                        && session.getMemcachedExpirationTime() <= 2 * delay ) {
                    try {
                        _backupSessionService.updateExpiration( session );
                    } catch ( final Throwable e ) {
                        _log.info( "Could not update expiration in memcached for session " + session.getId(), e );
                    }
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    public void propertyChange( final PropertyChangeEvent event ) {

        // Validate the source of this event
        if ( !( event.getSource() instanceof Context ) ) {
            return;
        }

        // Process a relevant property change
        if ( event.getPropertyName().equals( "sessionTimeout" ) ) {
            try {
                setMaxInactiveInterval( ( (Integer) event.getNewValue() ).intValue() * 60 );
            } catch ( final NumberFormatException e ) {
                _log.warn( "standardManager.sessionTimeout: " + event.getNewValue().toString() );
            }
        }

    }

    /**
     * Specifies if the session shall be stored asynchronously in memcached as
     * {@link MemcachedClient#set(String, int, Object)} supports it. If this is
     * false, the timeout set via {@link #setSessionBackupTimeout(int)} is
     * evaluated. If this is <code>true</code>, the {@link #setBackupThreadCount(int)}
     * is evaluated.
     * <p>
     * By default this property is set to <code>true</code> - the session
     * backup is performed asynchronously.
     * </p>
     *
     * @param sessionBackupAsync
     *            the sessionBackupAsync to set
     */
    public void setSessionBackupAsync( final boolean sessionBackupAsync ) {
        final boolean oldSessionBackupAsync = _sessionBackupAsync;
        _sessionBackupAsync = sessionBackupAsync;
        if ( ( oldSessionBackupAsync != sessionBackupAsync ) && initialized ) {
            _log.info( "SessionBackupAsync was changed to " + sessionBackupAsync + ", creating new BackupSessionService with new configuration." );
            _backupSessionService = new BackupSessionService( _transcoderService, _sessionBackupAsync, _sessionBackupTimeout,
                    _backupThreadCount, _memcached, _nodeIdService, _statistics );
        }
    }

    /**
     * The timeout in milliseconds after that a session backup is considered as
     * beeing failed.
     * <p>
     * This property is only evaluated if sessions are stored synchronously (set
     * via {@link #setSessionBackupAsync(boolean)}).
     * </p>
     * <p>
     * The default value is <code>100</code> millis.
     *
     * @param sessionBackupTimeout
     *            the sessionBackupTimeout to set (milliseconds)
     */
    public void setSessionBackupTimeout( final int sessionBackupTimeout ) {
        _sessionBackupTimeout = sessionBackupTimeout;
    }

    // ----------------------- protected getters/setters for testing ------------------

    /**
     * Set the {@link TranscoderService} that is used by this manager and the {@link BackupSessionService}.
     *
     * @param transcoderService the transcoder service to use.
     */
    void setTranscoderService( final TranscoderService transcoderService ) {
        _transcoderService = transcoderService;
        _backupSessionService = new BackupSessionService( transcoderService, _sessionBackupAsync, _sessionBackupTimeout,
                _backupThreadCount, _memcached, _nodeIdService, _statistics );
    }

    /**
     * Just for testing, DON'T USE THIS OTHERWISE!
     */
    void resetInitialized() {
        initialized = false;
    }

    /**
     * Return the currently configured node ids - just for testing.
     * @return the list of node ids.
     */
    List<String> getNodeIds() {
        return _nodeIdService.getNodeIds();
    }
    /**
     * Return the currently configured failover node ids - just for testing.
     * @return the list of failover node ids.
     */
    List<String> getFailoverNodeIds() {
        return _nodeIdService.getFailoverNodeIds();
    }

    /**
     * The memcached client.
     */
    MemcachedClient getMemcached() {
        return _memcached;
    }

    /**
     * The currently set locking strategy.
     */
    @Nullable
    LockingStrategy getLockingStrategy() {
        return _lockingStrategy;
    }

    // -------------------------  statistics via jmx ----------------

    /**
     * @return
     * @see de.javakaffee.web.msm.Statistics#getRequestsWithBackupFailure()
     */
    public long getMsmStatNumBackupFailures() {
        return _statistics.getRequestsWithBackupFailure();
    }

    /**
     * @return
     * @see de.javakaffee.web.msm.Statistics#getRequestsWithMemcachedFailover()
     */
    public long getMsmStatNumTomcatFailover() {
        return _statistics.getRequestsWithTomcatFailover();
    }

    /**
     * @return
     * @see de.javakaffee.web.msm.Statistics#getRequestsWithMemcachedFailover()
     */
    public long getMsmStatNumMemcachedFailover() {
        return _statistics.getRequestsWithMemcachedFailover();
    }

    /**
     * @return
     * @see de.javakaffee.web.msm.Statistics#getRequestsWithoutSession()
     */
    public long getMsmStatNumRequestsWithoutSession() {
        return _statistics.getRequestsWithoutSession();
    }

    /**
     * @return
     * @see de.javakaffee.web.msm.Statistics#getRequestsWithoutSessionAccess()
     */
    public long getMsmStatNumNoSessionAccess() {
        return _statistics.getRequestsWithoutSessionAccess();
    }

    /**
     * @return
     * @see de.javakaffee.web.msm.Statistics#getRequestsWithoutAttributesAccess()
     */
    public long getMsmStatNumNoAttributesAccess() {
        return _statistics.getRequestsWithoutAttributesAccess();
    }

    /**
     * @return
     * @see de.javakaffee.web.msm.Statistics#getRequestsWithoutSessionModification()
     */
    public long getMsmStatNumNoSessionModification() {
        return _statistics.getRequestsWithoutSessionModification();
    }

    /**
     * @return
     * @see de.javakaffee.web.msm.Statistics#getRequestsWithSession()
     */
    public long getMsmStatNumRequestsWithSession() {
        return _statistics.getRequestsWithSession();
    }

    public long getMsmStatNumNonStickySessionsPingFailed() {
        return _statistics.getNonStickySessionsPingFailed();
    }
    public long getMsmStatNumNonStickySessionsReadOnlyRequest() {
        return _statistics.getNonStickySessionsReadOnlyRequest();
    }

    /**
     * Returns a string array with labels and values of count, min, avg and max
     * of the time that took the attributes serialization.
     * @return a String array for statistics inspection via jmx.
     */
    public String[] getMsmStatAttributesSerializationInfo() {
        return _statistics.getProbe( ATTRIBUTES_SERIALIZATION ).getInfo();
    }

    /**
     * Returns a string array with labels and values of count, min, avg and max
     * of the time that session backups took in the request thread (including omitted
     * session backups e.g. because the session attributes were not accessed).
     * This time was spent in the request thread.
     *
     * @return a String array for statistics inspection via jmx.
     */
    public String[] getMsmStatEffectiveBackupInfo() {
        return _statistics.getProbe( EFFECTIVE_BACKUP ).getInfo();
    }

    /**
     * Returns a string array with labels and values of count, min, avg and max
     * of the time that session backups took (excluding backups where a session
     * was relocated). This time was spent in the request thread if session backup
     * is done synchronously, otherwise another thread used this time.
     *
     * @return a String array for statistics inspection via jmx.
     */
    public String[] getMsmStatBackupInfo() {
        return _statistics.getProbe( BACKUP ).getInfo();
    }

    /**
     * Returns a string array with labels and values of count, min, avg and max
     * of the time that loading sessions from memcached took (including deserialization).
     * @return a String array for statistics inspection via jmx.
     * @see #getMsmStatSessionDeserializationInfo()
     * @see #getMsmStatNonStickyAfterLoadFromMemcachedInfo()
     */
    public String[] getMsmStatSessionsLoadedFromMemcachedInfo() {
        return _statistics.getProbe( LOAD_FROM_MEMCACHED ).getInfo();
    }

    /**
     * Returns a string array with labels and values of count, min, avg and max
     * of the time that deleting sessions from memcached took.
     * @return a String array for statistics inspection via jmx.
     * @see #getMsmStatNonStickyAfterDeleteFromMemcachedInfo()
     */
    public String[] getMsmStatSessionsDeletedFromMemcachedInfo() {
        return _statistics.getProbe( DELETE_FROM_MEMCACHED ).getInfo();
    }

    /**
     * Returns a string array with labels and values of count, min, avg and max
     * of the time that deserialization of session data took.
     * @return a String array for statistics inspection via jmx.
     */
    public String[] getMsmStatSessionDeserializationInfo() {
        return _statistics.getProbe( SESSION_DESERIALIZATION ).getInfo();
    }

    /**
     * Returns a string array with labels and values of count, min, avg and max
     * of the size of the data that was sent to memcached.
     * @return a String array for statistics inspection via jmx.
     */
    public String[] getMsmStatCachedDataSizeInfo() {
        return _statistics.getProbe( CACHED_DATA_SIZE ).getInfo();
    }

    /**
     * Returns a string array with labels and values of count, min, avg and max
     * of the time that storing data in memcached took (excluding serialization,
     * including compression).
     * @return a String array for statistics inspection via jmx.
     */
    public String[] getMsmStatMemcachedUpdateInfo() {
        return _statistics.getProbe( MEMCACHED_UPDATE ).getInfo();
    }

    /**
     * Info about locks acquired in non-sticky mode.
     */
    public String[] getMsmStatNonStickyAcquireLockInfo() {
        return _statistics.getProbe( ACQUIRE_LOCK ).getInfo();
    }

    /**
     * Lock acquiration in non-sticky session mode.
     */
    public String[] getMsmStatNonStickyAcquireLockFailureInfo() {
        return _statistics.getProbe( ACQUIRE_LOCK_FAILURE ).getInfo();
    }

    /**
     * Lock release in non-sticky session mode.
     */
    public String[] getMsmStatNonStickyReleaseLockInfo() {
        return _statistics.getProbe( RELEASE_LOCK ).getInfo();
    }

    /**
     * Tasks executed (in the request thread) for non-sticky sessions at the end of requests that did not access
     * the session (validity load/update, ping session, ping 2nd session backup, update validity backup).
     */
    public String[] getMsmStatNonStickyOnBackupWithoutLoadedSessionInfo() {
        return _statistics.getProbe( NON_STICKY_ON_BACKUP_WITHOUT_LOADED_SESSION ).getInfo();
    }

    /**
     * Tasks executed for non-sticky sessions after session backup (ping session, store validity info / meta data,
     * store additional backup in secondary memcached).
     */
    public String[] getMsmStatNonStickyAfterBackupInfo() {
        return _statistics.getProbe( NON_STICKY_AFTER_BACKUP ).getInfo();
    }

    /**
     * Tasks executed for non-sticky sessions after a session was loaded from memcached (load validity info / meta data).
     */
    public String[] getMsmStatNonStickyAfterLoadFromMemcachedInfo() {
        return _statistics.getProbe( NON_STICKY_AFTER_LOAD_FROM_MEMCACHED ).getInfo();
    }

    /**
     * Tasks executed for non-sticky sessions after a session was deleted from memcached (delete validity info and backup data).
     */
    public String[] getMsmStatNonStickyAfterDeleteFromMemcachedInfo() {
        return _statistics.getProbe( NON_STICKY_AFTER_DELETE_FROM_MEMCACHED ).getInfo();
    }

    // ---------------------------------------------------------------------------
    
    /**
     * Memcached 节点信息配置信息
     */
        private static class MemcachedConfig {
        // 配置文件中的节点配置字符串
    	private final String _memcachedNodes;
    	// 配置文件中的错误节点字符串
        private final String _failoverNodes;
        // 根据配置文件，计算出所有的可用节点集合
        private final NodeIdList _nodeIds;
        // 将_failoverNodes字符串解析为 集合
        private final List<String> _failoverNodeIds;
        // 所有节点的 IP 套接字地址集合
        private final List<InetSocketAddress> _addresses;
        // 所有节点MAP：其中key为 IP 套接字地址集合,value 为 node节点信息
        private final Map<InetSocketAddress, String> _address2Ids;
       
        /**
         * Memcached 节点信息配置信息
         */
        public MemcachedConfig( final String memcachedNodes, final String failoverNodes,
                final NodeIdList nodeIds, final List<String> failoverNodeIds, final List<InetSocketAddress> addresses,
                final Map<InetSocketAddress, String> address2Ids ) {
            _memcachedNodes = memcachedNodes;
            _failoverNodes = failoverNodes;
            _nodeIds = nodeIds;
            _failoverNodeIds = failoverNodeIds;
            _addresses = addresses;
            _address2Ids = address2Ids;
        }

        /**
         * @return the number of all known memcached nodes.
         */
        public int getCountNodes() {
            return _addresses.size();
        }

        public String getMemcachedNodes() {
            return _memcachedNodes;
        }
        public String getFailoverNodes() {
            return _failoverNodes;
        }
        public NodeIdList getNodeIds() {
            return _nodeIds;
        }
        public List<String> getFailoverNodeIds() {
            return _failoverNodeIds;
        }
        public List<InetSocketAddress> getAddresses() {
            return _addresses;
        }
        public Map<InetSocketAddress, String> getAddress2Ids() {
            return _address2Ids;
        }
    }

}
