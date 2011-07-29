/*
 * Copyright 2010 Martin Grotzke
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
package de.javakaffee.kryoserializers;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.List;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.serialize.IntSerializer;

/**
 * A kryo {@link Serializer} for lists created via {@link List#subList(int, int)}.
 * 
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
@SuppressWarnings( "unchecked" )
public class SubListSerializer extends Serializer {
    
    private static final Class<?> SUBLIST_CLASS = getClass( "java.util.SubList" );

    private final Kryo _kryo;
    private Field _listField;
    private Field _offsetField;
    private Field _sizeField;

    public SubListSerializer( final Kryo kryo ) {
        _kryo = kryo;
        try {
            final Class<?> clazz = Class.forName( "java.util.SubList" );
            _listField = clazz.getDeclaredField( "l" );
            _offsetField = clazz.getDeclaredField( "offset" );
            _sizeField = clazz.getDeclaredField( "size" );
            _listField.setAccessible( true );
            _offsetField.setAccessible( true );
            _sizeField.setAccessible( true );
        } catch ( final Exception e ) {
            throw new RuntimeException( e );
        }
    }
    
    private static Class<?> getClass( final String className ) {
        try {
            return Class.forName( className );
        } catch ( final Exception e ) {
            throw new RuntimeException( e );
        }
    }

    /**
     * Can be used to determine, if the given type can be handled by this serializer.
     * @param type the class to check.
     * @return <code>true</code> if the given class can be serialized/deserialized by this serializer.
     */
    public static boolean canSerialize( final Class<?> type ) {
        return SUBLIST_CLASS.isAssignableFrom( type );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> T readObjectData( final ByteBuffer buffer, final Class<T> clazz ) {
        final List<?> list = (List<?>) _kryo.readClassAndObject( buffer );
        final int fromIndex = IntSerializer.get( buffer, true );
        final int toIndex = IntSerializer.get( buffer, true );
        return (T) list.subList( fromIndex, toIndex );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void writeObjectData( final ByteBuffer buffer, final Object obj ) {
        try {
            _kryo.writeClassAndObject( buffer, _listField.get( obj ) );
            final int fromIndex = _offsetField.getInt( obj );
            IntSerializer.put( buffer, fromIndex, true );
            final int toIndex = fromIndex + _sizeField.getInt( obj );
            IntSerializer.put( buffer, toIndex, true );
        } catch ( final RuntimeException e ) {
            // Don't eat and wrap RuntimeExceptions because the ObjectBuffer.write...
            // handles SerializationException specifically (resizing the buffer)...
            throw e;
        } catch ( final Exception e ) {
            throw new RuntimeException( e );
        }
    }

}
