/*
 * $Id$
 * (c) Copyright 2009 freiheit.com technologies GmbH
 *
 * Created on Mar 13, 2010 by Martin Grotzke (martin.grotzke@freiheit.com)
 *
 * This file contains unpublished, proprietary trade secret information of
 * freiheit.com technologies GmbH. Use, transcription, duplication and
 * modification are strictly prohibited without prior written consent of
 * freiheit.com technologies GmbH.
 */
package de.javakaffee.web.msm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPOutputStream;

import net.spy.memcached.DefaultConnectionFactory;
import net.spy.memcached.MemcachedClient;
import net.spy.memcached.compat.CloseUtil;
import net.spy.memcached.transcoders.SerializingTranscoder;
import net.spy.memcached.transcoders.Transcoder;



/**
 * @author Martin Grotzke (martin.grotzke@freiheit.com) (initial creation)
 */
public class MemcachedTest {

    private static final Logger LOG = Logger.getLogger( MemcachedTest.class.getName() );

    public static void main( final String[] args ) throws IOException, InterruptedException, ExecutionException {
        final MemcachedClient client = new MemcachedClient( new DefaultConnectionFactory(),
                Arrays.asList( new InetSocketAddress( "192.168.119.169", 11211 ) ) );
        final AtomicLong t = new AtomicLong();
        final SerializingTranscoder transcoder = new SerializingTranscoder() {
            @Override
            protected byte[] compress( final byte[] in ) {
                final long start = System.currentTimeMillis();
                try {
                    if(in == null) {
                        throw new NullPointerException("Can't compress null");
                    }
                    final ByteArrayOutputStream bos=new ByteArrayOutputStream();
                    GZIPOutputStream gz=null;
                    try {
                        gz = new GZIPOutputStream(bos);
                        gz.write(in);
                    } catch (final IOException e) {
                        throw new RuntimeException("IO exception compressing data", e);
                    } finally {
                        CloseUtil.close(gz);
                        CloseUtil.close(bos);
                    }
                    final byte[] rv=bos.toByteArray();
                    // getLogger().debug("Compressed %d bytes to %d", in.length, rv.length);
                    return rv;
                } finally {
                    t.addAndGet( System.currentTimeMillis() - start );
                }
            }
        };
        transcoder.setCompressionThreshold( 1024 * 1024 );

        final int numValues = 40;
        final String[] values = createValues( numValues );

        final int numThreads = 2;
        final ExecutorService executor = Executors.newFixedThreadPool( numThreads );
        try {

            warmUp( executor );

            final long start = System.currentTimeMillis();
            final int count = 1;

            executor.invokeAll( Arrays.asList(
                    new SetKeyValuesTask( 0, count, values, client, transcoder ),
                    new SetKeyValuesTask( 1 / 3 * count, count, values, client, transcoder ),
                    new SetKeyValuesTask( 2 / 3 * count, count, values, client, transcoder ),
                    new SetKeyValuesTask( count, count, values, client, transcoder ) ) );

            System.out.println( ( 4 * count ) + " set operations ("+ (2 * count) +" unique items) with "+ numThreads +" thread(s) took " + ( System.currentTimeMillis() - start ) + " msec." );
            System.out.println( "Compression took " + t.get() + " msec." );

            Thread.sleep( 500L );

            checkKey( client, 0 );
            checkKey( client, 1 );

            executor.awaitTermination( numThreads, TimeUnit.SECONDS );

        } catch ( final Exception e ) {
            LOG.log( Level.WARNING, "Caught exception.", e );
        } finally {
            executor.shutdown();
            client.shutdown();
        }

    }

    @SuppressWarnings( "unchecked" )
    private static void warmUp( final ExecutorService executor ) throws Exception {
        final Callable<Void> task = new Callable<Void>() {

            public Void call() throws Exception {
                return null;
            }

        };
        executor.invokeAll( Arrays.asList( task, task ) );
    }

    private static String[] createValues( final int numValues ) {
        final String[] values = new String[numValues];
        for( int i = 1; i <= numValues; i++ ) {
            values[i - 1] = newString( i );
        }
        return values;
    }

    private static void checkKey( final MemcachedClient client, final int i ) {
        final String value = (String) client.get( String.valueOf( i ) );
        System.out.println( "Key " + i + " available: " + (value != null) + (value != null ? " (size "+ value.length() + ")" : "") );
    }

    /**
     * @param i
     * @return
     */
    private static String newString( final int length  ) {
        final StringBuilder sb = new StringBuilder( length );
        final Random random = new Random();
        for( int i = 0; i < length * 1024; i++ ) {
            sb.append( random.nextInt( 9 ) );
        }
        return sb.toString();
    }

    private static final class SetKeyValuesTask implements Callable<Void> {

        private final int _start;
        private final int _count;
        private final String[] _values;
        private final MemcachedClient _client;
        private final Transcoder<Object> _transcoder;

        private SetKeyValuesTask( final int start, final int count, final String[] values, final MemcachedClient client,
                final Transcoder<Object> transcoder ) {
            _start = start;
            _count = count;
            _values = values;
            _client = client;
            _transcoder = transcoder;
        }

        public Void call() throws Exception {
            final int endIdx = _start + _count;
            for( int i = _start; i < endIdx; i++ ) {
                final int idx = i % _values.length;
                try {
                    _client.set( String.valueOf( i ), 3600, _values[idx], _transcoder ).get( 1000, TimeUnit.MILLISECONDS );
                } catch( final Exception e ) {
                    LOG.log( Level.WARNING, "Could not set key " + i, e );
                    throw e;
                }
            }
            return null;
        }
    }

    static class CustomSerializingTranscoder extends SerializingTranscoder {
        public CustomSerializingTranscoder( final int compressionThreshold ) {
            setCompressionThreshold( compressionThreshold );
        }
    }

}
