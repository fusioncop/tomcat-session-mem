package com.xylon;

import java.lang.reflect.InvocationHandler;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Currency;
import java.util.Date;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.ObjectBuffer;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.serialize.BigDecimalSerializer;
import com.esotericsoftware.kryo.serialize.BigIntegerSerializer;
import com.esotericsoftware.kryo.serialize.StringSerializer;

import de.javakaffee.kryoserializers.ArraysAsListSerializer;
import de.javakaffee.kryoserializers.ClassSerializer;
import de.javakaffee.kryoserializers.CollectionsEmptyListSerializer;
import de.javakaffee.kryoserializers.CollectionsEmptyMapSerializer;
import de.javakaffee.kryoserializers.CollectionsEmptySetSerializer;
import de.javakaffee.kryoserializers.CollectionsSingletonListSerializer;
import de.javakaffee.kryoserializers.CollectionsSingletonMapSerializer;
import de.javakaffee.kryoserializers.CollectionsSingletonSetSerializer;
import de.javakaffee.kryoserializers.CopyForIterateCollectionSerializer;
import de.javakaffee.kryoserializers.CopyForIterateMapSerializer;
import de.javakaffee.kryoserializers.CurrencySerializer;
import de.javakaffee.kryoserializers.EnumMapSerializer;
import de.javakaffee.kryoserializers.EnumSetSerializer;
import de.javakaffee.kryoserializers.GregorianCalendarSerializer;
import de.javakaffee.kryoserializers.JdkProxySerializer;
import de.javakaffee.kryoserializers.KryoReflectionFactorySupport;
import de.javakaffee.kryoserializers.KryoTest;
import de.javakaffee.kryoserializers.StringBufferSerializer;
import de.javakaffee.kryoserializers.StringBuilderSerializer;
import de.javakaffee.kryoserializers.SynchronizedCollectionsSerializer;
import de.javakaffee.kryoserializers.UnmodifiableCollectionsSerializer;

public class KRyoTest {
	private Kryo kryo;
	
	@Before
	public void before(){
		  kryo = new KryoReflectionFactorySupport() {
	            @Override
	            @SuppressWarnings( "unchecked" )
	            public Serializer newSerializer( final Class type ) {
	                if ( EnumSet.class.isAssignableFrom( type ) ) {
	                    return new EnumSetSerializer( this );
	                }
	                if ( EnumMap.class.isAssignableFrom( type ) ) {
	                    return new EnumMapSerializer( this );
	                }
	                if ( Collection.class.isAssignableFrom( type ) ) {
	                    return new CopyForIterateCollectionSerializer( this );
	                }
	                if ( Map.class.isAssignableFrom( type ) ) {
	                    return new CopyForIterateMapSerializer( this );
	                }
	                return super.newSerializer( type );
	            }
	        };
	        kryo.setRegistrationOptional( true );
//	        kryo.register( String.class, new StringSerializer());
//	        kryo.register( Arrays.asList( "" ).getClass(), new ArraysAsListSerializer( kryo ) );
//	        kryo.register( Currency.class, new CurrencySerializer( kryo ) );
//	        kryo.register( StringBuffer.class, new StringBufferSerializer( kryo ) );
//	        kryo.register( StringBuilder.class, new StringBuilderSerializer( kryo ) );
//	        kryo.register( Collections.EMPTY_LIST.getClass(), new CollectionsEmptyListSerializer() );
//	        kryo.register( Collections.EMPTY_MAP.getClass(), new CollectionsEmptyMapSerializer() );
//	        kryo.register( Collections.EMPTY_SET.getClass(), new CollectionsEmptySetSerializer() );
	        kryo.register( Collections.singletonList( "" ).getClass(), new CollectionsSingletonListSerializer( kryo ) );
//	        kryo.register( Collections.singleton( "" ).getClass(), new CollectionsSingletonSetSerializer( kryo ) );
//	        kryo.register( Collections.singletonMap( "", "" ).getClass(), new CollectionsSingletonMapSerializer( kryo ) );
//	        kryo.register( Class.class, new ClassSerializer( kryo ) );
//	        kryo.register( BigDecimal.class, new BigDecimalSerializer() );
//	        kryo.register( BigInteger.class, new BigIntegerSerializer() );
//	        kryo.register( GregorianCalendar.class, new GregorianCalendarSerializer() );
//	        kryo.register( InvocationHandler.class, new JdkProxySerializer( kryo ) );
	        UnmodifiableCollectionsSerializer.registerSerializers( kryo );
	        SynchronizedCollectionsSerializer.registerSerializers( kryo );
	}
	
	@Test
	public void test(){
		final List<?> obj = Collections.singletonList(1);
		
		final List<?> deserialized = new ObjectBuffer(kryo).readObject( new ObjectBuffer(kryo).writeObject(obj), obj.getClass() );
		System.out.println(deserialized.toString());
		
		final List<?> obj1 = Collections.singletonList("ss");
		
		final List<?> deserialized1 = new ObjectBuffer(kryo).readObject( new ObjectBuffer(kryo).writeObject(obj1), obj.getClass() );
		System.out.println(deserialized1.toString());
	}
}
