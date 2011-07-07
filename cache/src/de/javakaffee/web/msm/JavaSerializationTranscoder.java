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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.catalina.Loader;
import org.apache.catalina.Manager;
import org.apache.catalina.session.Constants;
import org.apache.catalina.util.CustomObjectInputStream;
import org.apache.catalina.util.StringManager;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;


/**
 * session attribute ���л� ����
 * @author wangx
 */
public class JavaSerializationTranscoder implements SessionAttributesTranscoder {
	
    private static final Log LOG = LogFactory.getLog( JavaSerializationTranscoder.class );

    private static final String EMPTY_ARRAY[] = new String[0];

    /**
     * The dummy attribute value serialized when a NotSerializableException is
     * encountered in <code>writeObject()</code>.
     */
    protected static final String NOT_SERIALIZED = "___NOT_SERIALIZABLE_EXCEPTION___";

    /**
     * The string manager for this package.
     */
    protected static StringManager sm = StringManager.getManager( Constants.Package );

    private final Manager _manager;

    /**
     * Constructor.
     *
     * @param manager
     *            the manager
     */
    public JavaSerializationTranscoder() {
        this( null );
    }

    /**
     * Constructor.
     *
     * @param manager
     *            the manager
     */
    public JavaSerializationTranscoder( final Manager manager ) {
        _manager = manager;
    }

    /**
     * ���л� Attributes with session
     * @author wangx
     */
    @Override
    public byte[] serializeAttributes( final MemcachedBackupSession session, final Map<String, Object> attributes ) {
        if ( attributes == null ) {
            throw new NullPointerException( "Can't serialize null" );
        }

        ByteArrayOutputStream bos = null;
        ObjectOutputStream oos = null;
        try {
            bos = new ByteArrayOutputStream();
            oos = new ObjectOutputStream( bos );

            writeAttributes( session, attributes, oos );

            return bos.toByteArray();
        } catch ( final IOException e ) {
            throw new IllegalArgumentException( "Non-serializable object", e );
        } finally {
            closeSilently( bos );
            closeSilently( oos );
        }

    }

    /**
     * �ҳ����Ա����л��Ķ���Ȼ�����������л�������
     * ��һ������Ϊ ���Ա����л����ϵĴ�Сֵ
     * ���Ϊ key value��Ӧ�ļ�ֵ�ԣ�
     * ��Ϊkeyһ����Ϊ�գ�����key���쳣���ʺ�С��
     * �ʵ����л�value�����쳣ʱ�������л�һ���쳣��ʶ 
     * @see de.javakaffee.web.msm.JavaSerializationTranscoder.NOT_SERIALIZED
     * @author wangx
     */
    private void writeAttributes( final MemcachedBackupSession session, final Map<String, Object> attributes,
            final ObjectOutputStream oos ) throws IOException {

        // Accumulate the names of serializable and non-serializable attributes
        final String keys[] = attributes.keySet().toArray( EMPTY_ARRAY );
        final List<String> saveNames = new ArrayList<String>();
        final List<Object> saveValues = new ArrayList<Object>();
        for ( int i = 0; i < keys.length; i++ ) {
            final Object value = attributes.get( keys[i] );
            if ( value == null || session.exclude( keys[i] ) ) {
                continue;
            } else if ( value instanceof Serializable ) {
                saveNames.add( keys[i] );
                saveValues.add( value );
            } else {
                if ( LOG.isDebugEnabled() ) {
                    LOG.debug( "Ignoring attribute '" + keys[i] + "' as it does not implement Serializable" );
                }
            }
        }

        // Serialize the attribute count and the Serializable attributes
        final int n = saveNames.size();
        oos.writeObject( new Integer( n ) );
        for ( int i = 0; i < n; i++ ) {
            oos.writeObject( saveNames.get( i ) );
            try {
                oos.writeObject( saveValues.get( i ) );
                if ( LOG.isDebugEnabled() ) {
                    LOG.debug( "  storing attribute '" + saveNames.get( i ) + "' with value '" + saveValues.get( i ) + "'" );
                }
            } catch ( final NotSerializableException e ) {
                oos.writeObject( NOT_SERIALIZED );
                LOG.warn( sm.getString( "standardSession.notSerializable", saveNames.get( i ), session.getIdInternal() ), e );
                if ( LOG.isDebugEnabled() ) {
                    LOG.debug( "  storing attribute '" + saveNames.get( i ) + "' with value NOT_SERIALIZED" );
                }
            }
        }

    }

   
    /**
     * �����л� Attributes with session
     * ��һ������Ϊ ���Ա����л����ϵĴ�Сֵ
     * ���Ϊ key value��Ӧ�ļ�ֵ�ԣ�
     * ��Ϊkeyһ����Ϊ�գ�����key���쳣���ʺ�С��
     * �ʵ����л�value�����쳣ʱ�������л�һ���쳣��ʶ 
     * @see de.javakaffee.web.msm.JavaSerializationTranscoder.NOT_SERIALIZED
     */
    @Override
    public Map<String, Object> deserializeAttributes( final byte[] in ) {
        ByteArrayInputStream bis = null;
        ObjectInputStream ois = null;
        try {
            bis = new ByteArrayInputStream( in );
            ois = createObjectInputStream( bis );

            final Map<String, Object> attributes = new ConcurrentHashMap<String, Object>();
            final int n = ( (Integer) ois.readObject() ).intValue();
            for ( int i = 0; i < n; i++ ) {
                final String name = (String) ois.readObject();
                final Object value = ois.readObject();
                if ( ( value instanceof String ) && ( value.equals( NOT_SERIALIZED ) ) ) {
                    continue;
                }
                if ( LOG.isDebugEnabled() ) {
                    LOG.debug( "  loading attribute '" + name + "' with value '" + value + "'" );
                }
                attributes.put( name, value );
            }

            return attributes;
        } catch ( final ClassNotFoundException e ) {
            LOG.warn( "Caught CNFE decoding "+ in.length +" bytes of data", e );
            throw new RuntimeException( "Caught CNFE decoding data", e );
        } catch ( final IOException e ) {
            LOG.warn( "Caught IOException decoding "+ in.length +" bytes of data", e );
            throw new RuntimeException( "Caught IOException decoding data", e );
        } finally {
            closeSilently( bis );
            closeSilently( ois );
        }
    }

    /**
     * ClassLoader and Loader   ???
     * 
     * @author wangx
     */
    private ObjectInputStream createObjectInputStream( final ByteArrayInputStream bis ) throws IOException {
        final ObjectInputStream ois;
        Loader loader = null;
        ClassLoader classLoader = null;
        if ( _manager != null && _manager.getContainer() != null ) {
            loader = _manager.getContainer().getLoader();
        }
        if ( loader != null ) {
            classLoader = loader.getClassLoader();
        }
        if ( classLoader != null ) {
            ois = new CustomObjectInputStream( bis, classLoader );
        } else {
            ois = new ObjectInputStream( bis );
        }
        return ois;
    }

    /**
     * �رճ���
     * @author wangx
     */
    private void closeSilently( final OutputStream os ) {
        if ( os != null ) {
            try {
                os.close();
            } catch ( final IOException f ) {
                // fail silently
            }
        }
    }
    
    /**
     * �ر�����
     * @author wangx
     */
    private void closeSilently( final InputStream is ) {
        if ( is != null ) {
            try {
                is.close();
            } catch ( final IOException f ) {
                // fail silently
            }
        }
    }

}
