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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

/**
 * ����ڵ�״̬��Ϣ
 * An LRUCache that supports a maximum number of cache entries and a time to
 * live for them. The TTL is measured from insertion time to access time.
 *
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 * @version $Id$
 * @param <K>
 *            the type of the key
 */
public class NodeAvailabilityCache<K> {

    private static final Log LOG = LogFactory.getLog( NodeAvailabilityCache.class );
    //��Чʱ��
    private final long _ttl;
    //����Ľڵ�״̬
    private final ConcurrentHashMap<K, ManagedItem<Boolean>> _map;
    //�ڵ��Ƿ���Ч
    private final CacheLoader<K> _cacheLoader;

    /**
     * Node ��Žڵ�	NodeAvailabilityCache <br/>
     * Create a new LRUCache with a maximum number of cache entries and a
     * specified time to live for cache entries. The TTL is measured from
     * insertion time to access time.
     *
     * @param size
     *            the maximum number of cached items
     * @param ttlInMillis
     *            the time to live in milli seconds. Specify -1 for no limit
     * @param cacheLoader
     *            the cache loader to use
     */
    public NodeAvailabilityCache( final int size, final long ttlInMillis, final CacheLoader<K> cacheLoader ) {
        _ttl = ttlInMillis;
        _map = new ConcurrentHashMap<K, ManagedItem<Boolean>>( size / 2 );
        _cacheLoader = cacheLoader;
    }

    /**
     * 
     * �趨 nodeid ��״̬��true ����OR  false ������
     * If the specified key is not already associated with a value or if it's
     * associated with a different value, associate it with the given value.
     * This is equivalent to
     *
     * <pre>
     * <code> if (map.get(key) == null || !map.get(key).equals(value))
     *    return map.put(key, value);
     * else
     *    return map.get(key);
     * </code>
     * </pre>
     *
     * except that the action is performed atomically.
     *
     * @param key
     *            the key to associate the value with.
     * @param available
     *            the value to associate with the provided key.
     * @return the previous value associated with the specified key, or null if
     *         there was no mapping for the key
     */
    public Boolean setNodeAvailable( final K key, final boolean available ) {
        final ManagedItem<Boolean> item = _map.get( key );
        final Boolean availableObj = Boolean.valueOf( available );
        if ( item == null || item._value != availableObj ) {
            final ManagedItem<Boolean> previous =
                    _map.put( key, new ManagedItem<Boolean>( availableObj, System.currentTimeMillis() ) );
            return previous != null
                ? previous._value
                : null;
        } else {
            return item._value;
        }
    }

    /**
     * �жϸýڵ� �Ƿ���Ч
     * Determines, if the node is available. If it's not cached, it's loaded
     * from the cache loader.
     *
     * @param key
     *            the key to check
     * @return <code>true</code> if the node is marked as available.
     */
    public boolean isNodeAvailable( @Nonnull final K key ) {
        final ManagedItem<Boolean> item = _map.get( key );
        if ( item == null ) {
            return updateIsNodeAvailable( key );
        } else if ( isExpired( item ) ) {
            _map.remove( key );
            return updateIsNodeAvailable( key );
        } else {
            return item._value;
        }
    }

    /**
     * ������Ƿ���Ч ����true ��Ϊ ʧЧ�� ��֮ ��Ч
     * ��ǰʱ�� - �ڵ�Ļ���ʱ�䣬����С�ڳ�ʱʱ�䣬����Ч��
     * @param item
     * @return
     */
    private boolean isExpired( final ManagedItem<Boolean> item ) {
        return _ttl > -1 && System.currentTimeMillis() - item._insertionTime > _ttl;
    }

    /**
     * ���¼��ýڵ��Ƿ���Ч
     * @param key
     * @return
     */
    private boolean updateIsNodeAvailable( final K key ) {
        final Boolean result = Boolean.valueOf( _cacheLoader.isNodeAvailable( key ) );

        if ( LOG.isDebugEnabled() ) {
            LOG.debug( "CacheLoader returned node availability '" + result + "' for node '" + key + "'." );
        }

        _map.put( key, new ManagedItem<Boolean>( result, System.currentTimeMillis() ) );
        return result;
    }

    /**
     * ������нڵ�����
     * All known keys.
     * @return a list of all keys, never <code>null</code>.
     */
    public List<K> getKeys() {
        return new ArrayList<K>( _map.keySet() );
    }

    /**
     * �������ʧЧ�Ľڵ�
     * A set of nodes that are stored as unavailable.
     * @return a set of unavailable nodes, never <code>null</code>.
     */
    public Set<K> getUnavailableNodes() {
        final Set<K> result = new HashSet<K>();
        for ( final Map.Entry<K, ManagedItem<Boolean>> entry : _map.entrySet() ) {
            if ( !entry.getValue()._value.booleanValue() && !isExpired( entry.getValue() ) ) {
                result.add( entry.getKey() );
            }
        }
        return result;
    }

    /**
     * ����ڵ�״̬��Ϣ
     * Stores a value with the timestamp this value was added to the cache.
     *
     * @param <T>
     *            the type of the value
     */
    private static final class ManagedItem<T> {
    	//true ��Ч or false ʧЧ
        private final T _value;
        //����ʱ�䣬�����жϳ�ʱ���
        private final long _insertionTime;

        /**
         * ����ڵ�״̬��Ϣ
         * @param value
         * @param accessTime
         */
        private ManagedItem( final T value, final long accessTime ) {
            _value = value;
            _insertionTime = accessTime;
        }
    }

    /**
     * memcached �ڵ��Ƿ���Ч
     * The cache loader interface.
     *
     * @param <K>
     *            the type of the key.
     */
    static interface CacheLoader<K> {
    	
    	/**
    	 * memcached �ڵ�״̬��Ϣ
    	 * @return
    	 */
        boolean isNodeAvailable( K key );

    }

}
