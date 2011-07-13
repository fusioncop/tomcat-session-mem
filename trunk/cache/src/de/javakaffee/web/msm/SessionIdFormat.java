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

import java.util.regex.Pattern;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

/**
 * sessionid 帮助类 ：
 * 原始的sessionid包括sessionid+"."+clusterId,
 * 被包装后的sessionid为<b>sessionID-memcachedId(.clusterId)? </b>
 * <br/>
 * This class defines the session id format: It creates session ids based on the
 * original session id and the memcached id, and it extracts the session id and
 * memcached id from a compound session id.
 * <p>
 * The session id is of the following format:
 * <code>[^-.]+-[^.]+(\.[^.]+)?</code>
 * </p>
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 * @version $Id$
 */
public class SessionIdFormat {

    private static final String BACKUP_PREFIX = "bak:";

    private static final Log LOG = LogFactory.getLog( SessionIdFormat.class );

    /**
     * The pattern for the session id.
     */
    private final Pattern _pattern = Pattern.compile( "[^-.]+-[^.]+(\\.[^.]+)?" );

    /**
     * 根据sessionId 和 memcachedId 创建 新的sessionId
     * SESSIONID的格式：sessionID-memcachedId(.clusterId)?
     * @param sessionId		有可能包含jvmRouteID
     * @param memcachedId	MemcachedId
     * @return
     */
    @Nonnull
    public String createSessionId( @Nonnull final String sessionId, @Nullable final String memcachedId ) {
        if ( LOG.isDebugEnabled() ) {
            LOG.debug( "Creating new session id with orig id '" + sessionId + "' and memcached id '" + memcachedId + "'." );
        }
        if ( memcachedId == null ) {
            return sessionId;
        }
        final int idx = sessionId.indexOf( '.' );
        if ( idx < 0 ) {
            return sessionId + "-" + memcachedId;
        } else {
            return sessionId.substring( 0, idx ) + "-" + memcachedId + sessionId.substring( idx );
        }
    }

    /**
     * 根据sessionId 和 新的memcachedId 创建 新的sessionId
     * @param sessionId			有可能包含jvmRouteID
     * @param newMemcachedId	新的MemcachedId
     * @return
     */
    @Nonnull
    public String createNewSessionId( @Nonnull final String sessionId, @Nonnull final String newMemcachedId ) {
        final int idxDot = sessionId.indexOf( '.' );
        //包含 jvmRouteID
        if ( idxDot != -1 ) {
            final String plainSessionId = sessionId.substring( 0, idxDot );
            final String jvmRouteWithDot = sessionId.substring( idxDot );
            return appendOrReplaceMemcachedId( plainSessionId, newMemcachedId ) + jvmRouteWithDot;
        }
        //不包含 jvmRouteID
        else {
            return appendOrReplaceMemcachedId( sessionId, newMemcachedId );
        }
    }

    /**
     * 将不包含	jvmRouteID 的sessionID 和 newMemcachedId 组合成新的sessionID
     * @param sessionId			不包含	jvmRouteID
     * @param newMemcachedId	newMemcachedId
     * @return
     */
    @Nonnull
    private String appendOrReplaceMemcachedId( @Nonnull final String sessionId, @Nonnull final String newMemcachedId ) {
        final int idxDash = sessionId.indexOf( '-' );
        if ( idxDash < 0 ) {
            return sessionId + "-" + newMemcachedId;
        } else {
            return sessionId.substring( 0, idxDash + 1 ) + newMemcachedId;
        }
    }

    /**
     * 去掉 sessionId 中包含 的旧的jvmRouteID, 并添加新的newJvmRouteID
     * @param sessionId
     * @param newJvmRoute
     * @return
     */
    @Nonnull
    public String changeJvmRoute( @Nonnull final String sessionId, @Nonnull final String newJvmRoute ) {
        return stripJvmRoute( sessionId ) + "." + newJvmRoute;
    }

    /**
     * 检查sessionID是否符合 sessionID的规则（sessionID-memcachedId(.clusterId)?）
     * @param sessionId
     * @return
     */
    public boolean isValid( @Nullable final String sessionId ) {
        return sessionId != null && _pattern.matcher( sessionId ).matches();
    }

    /**
     * 从sessionId中提取memcacheId <br/>
     * @param sessionId	sessionId
     * @return
     */
    @CheckForNull
    public String extractMemcachedId( @Nonnull final String sessionId ) {
        final int idxDash = sessionId.indexOf( '-' );
        if ( idxDash < 0 ) {
            return null;
        }
        final int idxDot = sessionId.indexOf( '.' );
        if ( idxDot < 0 ) {
            return sessionId.substring( idxDash + 1 );
        } else if ( idxDot < idxDash ) /* The dash was part of the jvmRoute */ {
            return null;
        } else {
            return sessionId.substring( idxDash + 1, idxDot );
        }
    }

    /**
     * 从sessionId中提取jvmRouteID 
     * eg. sessionId + "." + jvmRouteID <br/>
     * Extract the jvm route from the given session id if existing.
     * @param sessionId  the session id possibly including the memcached id and eventually the
     *            jvmRoute.
     * @return the jvm route or null if the session id didn't contain any.
     */
    @CheckForNull
    public String extractJvmRoute( @Nonnull final String sessionId ) {
        final int idxDot = sessionId.indexOf( '.' );
        return idxDot < 0 ? null : sessionId.substring( idxDot + 1 );
    }

    /**
     * 去掉 sessionId 中包含 的jvmRouteID 
     * @param sessionId	 sessionId
     * @return
     */
    @Nonnull
    public String stripJvmRoute( @Nonnull final String sessionId ) {
        final int idxDot = sessionId.indexOf( '.' );
        return idxDot < 0 ? sessionId : sessionId.substring( 0, idxDot );
    }

    /**
     * 获得锁名称（"lock:" + sessionId）---用来存放于memcached中
     * @param sessionId
     * @return
     */
    @Nonnull
    public String createLockName( @Nonnull final String sessionId ) {
        if ( sessionId == null ) {
            throw new IllegalArgumentException( "The sessionId must not be null." );
        }
        return "lock:" + sessionId;
    }

    /**
     * 获得锁名称（"bak:" + sessionId）---用来存放于memcached中
     * Creates the name/key that is used for the data (session or validity info)
     * that is additionally stored in a secondary memcached node for non-sticky sessions.
     * @param origKey the session id (or validity info key) for that a key shall be created.
     * @return a String.
     */
    @Nonnull
    public String createBackupKey( @Nonnull final String origKey ) {
        if ( origKey == null ) {
            throw new IllegalArgumentException( "The origKey must not be null." );
        }
        return BACKUP_PREFIX + origKey;
    }

    /**
     * 验证是否为BackupKey
     * @param key
     * @return
     */
    public boolean isBackupKey( @Nonnull final String key ) {
        return key.startsWith( BACKUP_PREFIX );
    }

}