package org.quickload.config;

import java.lang.reflect.Proxy;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.io.IOException;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.deser.Deserializers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.JsonMappingException;

class TaskSerDe
{
    public static class TaskSerializer
            extends JsonSerializer<Task>
    {
        private final ObjectMapper nestedObjectMapper;

        public TaskSerializer(ObjectMapper nestedObjectMapper)
        {
            this.nestedObjectMapper = nestedObjectMapper;
        }

        @Override
        public void serialize(Task value, JsonGenerator jgen, SerializerProvider provider)
                throws IOException
        {
            if (value instanceof Proxy) {
                Object handler = Proxy.getInvocationHandler(value);
                if (handler instanceof TaskInvocationHandler) {
                    TaskInvocationHandler h = (TaskInvocationHandler) handler;
                    Map<String, Object> objects = h.getObjects();
                    jgen.writeStartObject();
                    for (Map.Entry<String, Object> pair : objects.entrySet()) {
                        jgen.writeFieldName(pair.getKey());
                        nestedObjectMapper.writeValue(jgen, pair.getValue());
                    }
                    jgen.writeEndObject();
                    return;
                }
            }
            // TODO exception class & message
            throw new UnsupportedOperationException("Serializing Task is not supported");
        }
    }

    public static class TaskDeserializer <T>
            extends JsonDeserializer<T>
    {
        private final ObjectMapper nestedObjectMapper;
        private final TaskValidator taskValidator;
        private final Class<?> iface;
        private final Map<String, FieldEntry> mappings;

        public TaskDeserializer(ObjectMapper nestedObjectMapper, TaskValidator taskValidator, Class<T> iface)
        {
            this.nestedObjectMapper = nestedObjectMapper;
            this.taskValidator = taskValidator;
            this.iface = iface;
            this.mappings = getterMappings(iface);
        }

        protected Map<String, FieldEntry> getterMappings(Class<?> iface)
        {
            ImmutableMap.Builder<String, FieldEntry> builder = ImmutableMap.builder();
            for (Map.Entry<String, Method> getter : TaskInvocationHandler.fieldGetters(iface).entrySet()) {
                Method getterMethod = getter.getValue();
                String fieldName = getter.getKey();
                Type fieldType = getterMethod.getGenericReturnType();

                Optional<String> jsonKey = getJsonKey(getterMethod, fieldName);
                if (!jsonKey.isPresent()) {
                    // skip this field
                    continue;
                }
                Optional<String> defaultJsonString = getDefaultJsonString(getterMethod);
                builder.put(jsonKey.get(), new FieldEntry(fieldName, fieldType, defaultJsonString));
            }
            return builder.build();
        }

        protected Optional<String> getJsonKey(Method getterMethod, String fieldName)
        {
            return Optional.of(fieldName);
        }

        protected Optional<String> getDefaultJsonString(Method getterMethod)
        {
            return Optional.absent();
        }

        @Override
        public T deserialize(JsonParser jp, DeserializationContext ctxt) throws IOException
        {
            Map<String, Object> objects = new ConcurrentHashMap<String, Object>();
            HashMap<String, FieldEntry> unusedMappings = new HashMap<>(mappings);

            JsonToken current;
            current = jp.getCurrentToken();
            if (current == JsonToken.START_OBJECT) {
                current = jp.nextToken();
            }
            for (; current != JsonToken.END_OBJECT; current = jp.nextToken()) {
                String key = jp.getCurrentName();
                current = jp.nextToken();
                FieldEntry field = mappings.get(key);
                if (field == null) {
                    jp.skipChildren();
                } else {
                    Object value = nestedObjectMapper.readValue(jp, new GenericTypeReference(field.getType()));
                    if (value == null) {
                        throw new JsonMappingException("Setting null to a @Config field is not allowed. Configuration object must be Optional<T> (com.google.common.base.Optional)");
                    }
                    objects.put(field.getName(), value);
                    unusedMappings.remove(key);
                }
            }

            // set default values
            for (Map.Entry<String, FieldEntry> unused : unusedMappings.entrySet()) {
                FieldEntry field = unused.getValue();
                if (field.getDefaultJsonString().isPresent()) {
                    Object value = nestedObjectMapper.readValue(field.getDefaultJsonString().get(), new GenericTypeReference(field.getType()));
                    if (value == null) {
                        throw new JsonMappingException("Setting null to a @Config field is not allowed. Configuration object must be Optional<T> (com.google.common.base.Optional)");
                    }
                    objects.put(field.getName(), value);
                } else {
                    // required field
                    throw new JsonMappingException("Field '"+unused.getKey()+"' is required but not set", jp.getCurrentLocation());
                }
            }

            return (T) Proxy.newProxyInstance(
                    iface.getClassLoader(), new Class<?>[] { iface },
                    new TaskInvocationHandler(iface, taskValidator, objects));
        }

        private static class FieldEntry
        {
            private final String name;
            private final Type type;
            private final Optional<String> defaultJsonString;

            public FieldEntry(String name, Type type, Optional<String> defaultJsonString)
            {
                this.name = name;
                this.type = type;
                this.defaultJsonString = defaultJsonString;
            }

            public String getName()
            {
                return name;
            }

            public Type getType()
            {
                return type;
            }

            public Optional<String> getDefaultJsonString()
            {
                return defaultJsonString;
            }
        }
    }

    public static class TaskSerializerModule
            extends SimpleModule
    {
        public TaskSerializerModule(ObjectMapper nestedObjectMapper)
        {
            super();
            addSerializer(Task.class, new TaskSerializer(nestedObjectMapper));
        }
    }

    public static class ConfigTaskDeserializer <T>
            extends TaskDeserializer<T>
    {
        public ConfigTaskDeserializer(ObjectMapper nestedObjectMapper, TaskValidator taskValidator, Class<T> iface)
        {
            super(nestedObjectMapper, taskValidator, iface);
        }

        @Override
        protected Optional<String> getJsonKey(Method getterMethod, String fieldName)
        {
            Config a = getterMethod.getAnnotation(Config.class);
            if (a != null) {
                return Optional.of(a.value());
            } else {
                return Optional.absent();  // skip this field
            }
        }

        @Override
        public Optional<String> getDefaultJsonString(Method getterMethod)
        {
            ConfigDefault a = getterMethod.getAnnotation(ConfigDefault.class);
            if (a != null && !a.value().isEmpty()) {
                return Optional.of(a.value());
            }
            return super.getDefaultJsonString(getterMethod);
        }
    }

    public static class TaskDeserializerModule
            extends Module // can't use just SimpleModule, due to generic types
    {
        protected final ObjectMapper nestedObjectMapper;
        protected final TaskValidator taskValidator;

        public TaskDeserializerModule(ObjectMapper nestedObjectMapper, TaskValidator taskValidator)
        {
            this.nestedObjectMapper = nestedObjectMapper;
            this.taskValidator = taskValidator;
        }

        @Override
        public String getModuleName() { return "quickload.config.TaskSerDe"; }

        @Override
        public Version version() { return Version.unknownVersion(); }

        @Override
        public void setupModule(SetupContext context)
        {
            context.addDeserializers(new Deserializers.Base() {
                @Override
                public JsonDeserializer<?> findBeanDeserializer(JavaType type, DeserializationConfig config,
                        BeanDescription beanDesc) throws JsonMappingException
                {
                    Class<?> raw = type.getRawClass();
                    if (Task.class.isAssignableFrom(raw)) {
                        return newTaskDeserializer(raw);
                    }
                    return super.findBeanDeserializer(type, config, beanDesc);
                }
            });
        }

        protected JsonDeserializer<?> newTaskDeserializer(Class<?> raw)
        {
            return new TaskDeserializer(nestedObjectMapper, taskValidator, raw);
        }
    }

    public static class ConfigTaskDeserializerModule
            extends TaskDeserializerModule
    {
        public ConfigTaskDeserializerModule(ObjectMapper nestedObjectMapper, TaskValidator taskValidator)
        {
            super(nestedObjectMapper, taskValidator);
        }

        @Override
        public String getModuleName() { return "quickload.config.ConfigTaskSerDe"; }

        @Override
        protected JsonDeserializer<?> newTaskDeserializer(Class<?> raw)
        {
            return new ConfigTaskDeserializer(nestedObjectMapper, taskValidator, raw);
        }
    }
}