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
package de.javakaffee.web.msm.serializer.kryo;

import org.apache.wicket.Component;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;

import de.javakaffee.kryoserializers.ReferenceFieldSerializerReflectionFactorySupport;

/**
 * A {@link KryoCustomization} that creates a {@link ReferenceFieldSerializerReflectionFactorySupport} as
 * serializer for subclasses of {@link Component}. This is required, as the {@link Component}
 * constructor invokes {@link org.apache.wicket.Application#get()} to tell the application
 * to {@link org.apache.wicket.Application#notifyComponentInstantiationListeners()}. This will
 * lead to NullpointerExceptions if the application is not yet bound to the current thread
 * because the session is e.g. accessed from within a servlet filter. If the component is created
 * via the constructor for serialization, this problem does not occur.
 * 
 * @author <a href="mailto:martin.grotzke@javakaffee.de">Martin Grotzke</a>
 */
public class ComponentSerializerFactory implements SerializerFactory {

    private final Kryo _kryo;
    
    public ComponentSerializerFactory( final Kryo kryo ) {
        _kryo = kryo;
    }
    
    @Override
    public Serializer newSerializer( final Class<?> type ) {
        if ( Component.class.isAssignableFrom( type ) ) {
            final ReferenceFieldSerializerReflectionFactorySupport result = new ReferenceFieldSerializerReflectionFactorySupport( _kryo, type );
            result.setIgnoreSyntheticFields( false );
            return result;
        }
        return null;
    }
    
}
