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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.spy.memcached.MemcachedNode;
import net.spy.memcached.NodeLocator;
import net.spy.memcached.ops.Operation;

/**
 * MemcachedNode �ڵ������ 
 * Locates nodes based on their id which is a part of the sessionId (key).
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 * @version $Id$
 */
class SuffixBasedNodeLocator implements NodeLocator {

	//MemcachedNode 
    private final List<MemcachedNode> _nodes;
    //���п��õĽڵ�
    private final NodeIdList _nodeIds;
    //
    private final NodeIdResolver _resolver;
    // nodeid MemcachedNode
    private final Map<String, MemcachedNode> _nodesMap;
    private final SessionIdFormat _sessionIdFormat;

    /**
     * MemcachedNode �ڵ������ <br/>
     * Create a new {@link SuffixBasedNodeLocator}.
     *
     * @param nodes
     *            the nodes to select from.
     * @param nodeIds
     *            the list of nodeIds.
     * @param resolver
     *            used to resolve the node id for the address of a memcached
     *            node.
     * @param sessionIdFormat
     *            used to extract the node id from the session id.
     */
    public SuffixBasedNodeLocator( final List<MemcachedNode> nodes, final NodeIdList nodeIds, final NodeIdResolver resolver,
            final SessionIdFormat sessionIdFormat ) {
        _nodes = nodes;
        _nodeIds = nodeIds;
        _resolver = resolver;

        final Map<String, MemcachedNode> map = new HashMap<String, MemcachedNode>( nodes.size(), 1 );
        for ( int i = 0; i < nodes.size(); i++ ) {
            final MemcachedNode memcachedNode = nodes.get( i );
            final String nodeId = resolver.getNodeId( (InetSocketAddress) memcachedNode.getSocketAddress() );
            map.put( nodeId, memcachedNode );
        }
        _nodesMap = map;

        _sessionIdFormat = sessionIdFormat;
    }

   /**
    * ���� MemcachedNode �ڵ�
    */
    public Collection<MemcachedNode> getAll() {
        return _nodesMap.values();
    }

    /**
     * ����key���ҽڵ�
     */
    public MemcachedNode getPrimary( final String key ) {
        final MemcachedNode result = _nodesMap.get( getNodeId( key ) );
        if ( result == null ) {
            throw new IllegalArgumentException( "No node found for key " + key );
        }
        return result;
    }
   
    /**
     * ����key����
     * @return
     */
    private String getNodeId( final String key ) {
        final String nodeId = _sessionIdFormat.extractMemcachedId( key );
        if ( !_sessionIdFormat.isBackupKey( key ) ) {
            return nodeId;
        }
        return _nodeIds.getNextNodeId( nodeId );
    }

    /**
     * ������ MemcachedNode
     */
    //TODO why throw Exception
    public Iterator<MemcachedNode> getSequence( final String key ) {
        throw new UnsupportedOperationException( "This should not be called as we specified FailureMode.Cancel." );
    }

    /**
     * ֻ���ڵ� 
     */
    //TODO �ڵ�ͬ����memcached ������ʵ�� OR memcache jar ʵ�֣����о�
    public NodeLocator getReadonlyCopy() {
        final List<MemcachedNode> nodes = new ArrayList<MemcachedNode>();
        for ( final MemcachedNode node : _nodes ) {
            nodes.add( new MyMemcachedNodeROImpl( node ) );
        }
        return new SuffixBasedNodeLocator( nodes, _nodeIds, _resolver, _sessionIdFormat );
    }

    /**
     * readonly copies �ڵ�
     * The class that is used for readonly copies.
     */
    static class MyMemcachedNodeROImpl implements MemcachedNode {

        private final MemcachedNode _root;

        public MyMemcachedNodeROImpl( final MemcachedNode node ) {
            _root = node;
        }

        /**
         * {@inheritDoc}
         */
        public String toString() {
            return _root.toString();
        }

        /**
         * {@inheritDoc}
         */
        public void addOp( final Operation op ) {
            throw new UnsupportedOperationException();
        }

        /**
         * {@inheritDoc}
         */
        public void connected() {
            throw new UnsupportedOperationException();
        }

        /**
         * {@inheritDoc}
         */
        public void copyInputQueue() {
            throw new UnsupportedOperationException();
        }

        /**
         * {@inheritDoc}
         */
        public void fillWriteBuffer( final boolean optimizeGets ) {
            throw new UnsupportedOperationException();
        }

        /**
         * {@inheritDoc}
         */
        public void fixupOps() {
            throw new UnsupportedOperationException();
        }

        /**
         * {@inheritDoc}
         */
        public int getBytesRemainingToWrite() {
            throw new UnsupportedOperationException();
        }

        /**
         * {@inheritDoc}
         */
        public SocketChannel getChannel() {
            throw new UnsupportedOperationException();
        }

        /**
         * {@inheritDoc}
         */
        public Operation getCurrentReadOp() {
            throw new UnsupportedOperationException();
        }

        /**
         * {@inheritDoc}
         */
        public Operation getCurrentWriteOp() {
            throw new UnsupportedOperationException();
        }

        /**
         * {@inheritDoc}
         */
        public ByteBuffer getRbuf() {
            throw new UnsupportedOperationException();
        }

        /**
         * {@inheritDoc}
         */
        public int getReconnectCount() {
            throw new UnsupportedOperationException();
        }

        /**
         * {@inheritDoc}
         */
        public int getSelectionOps() {
            throw new UnsupportedOperationException();
        }

        /**
         * {@inheritDoc}
         */
        public SelectionKey getSk() {
            throw new UnsupportedOperationException();
        }

        /**
         * {@inheritDoc}
         */
        public SocketAddress getSocketAddress() {
            return _root.getSocketAddress();
        }

        /**
         * {@inheritDoc}
         */
        public ByteBuffer getWbuf() {
            throw new UnsupportedOperationException();
        }

        /**
         * {@inheritDoc}
         */
        public boolean hasReadOp() {
            throw new UnsupportedOperationException();
        }

        /**
         * {@inheritDoc}
         */
        public boolean hasWriteOp() {
            throw new UnsupportedOperationException();
        }

        /**
         * {@inheritDoc}
         */
        public boolean isActive() {
            throw new UnsupportedOperationException();
        }

        /**
         * {@inheritDoc}
         */
        public void reconnecting() {
            throw new UnsupportedOperationException();
        }

        /**
         * {@inheritDoc}
         */
        public void registerChannel( final SocketChannel ch, final SelectionKey selectionKey ) {
            throw new UnsupportedOperationException();
        }

        /**
         * {@inheritDoc}
         */
        public Operation removeCurrentReadOp() {
            throw new UnsupportedOperationException();
        }

        /**
         * {@inheritDoc}
         */
        public Operation removeCurrentWriteOp() {
            throw new UnsupportedOperationException();
        }

        /**
         * {@inheritDoc}
         */
        public void setChannel( final SocketChannel to ) {
            throw new UnsupportedOperationException();
        }

        /**
         * {@inheritDoc}
         */
        public void setSk( final SelectionKey to ) {
            throw new UnsupportedOperationException();
        }

        /**
         * {@inheritDoc}
         */
        public void setupResend() {
            throw new UnsupportedOperationException();
        }

        /**
         * {@inheritDoc}
         */
        public void transitionWriteItem() {
            throw new UnsupportedOperationException();
        }

        /**
         * {@inheritDoc}
         */
        public int writeSome() throws IOException {
            throw new UnsupportedOperationException();
        }

        /**
         * {@inheritDoc}
         */
        public Collection<Operation> destroyInputQueue() {
            throw new UnsupportedOperationException();
        }
        @Override
        public void authComplete() {
            throw new UnsupportedOperationException();
        }
        @Override
        public void insertOp( final Operation arg0 ) {
            throw new UnsupportedOperationException();
        }
        @Override
        public void setupForAuth() {
            throw new UnsupportedOperationException();
        }
    }

}