package org.embulk.type;

import java.util.HashMap;
import java.util.Map;
import java.io.IOException;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.FromStringDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import org.embulk.config.ModelManager;

public class TypeDeserializer
        extends FromStringDeserializer<Type>
{
    private static final Map<String, Type> stringToTypeMap;

    static {
        ImmutableMap.Builder<String, Type> builder = ImmutableMap.builder();
        builder.put(BooleanType.BOOLEAN.getName(), BooleanType.BOOLEAN);
        builder.put(LongType.LONG.getName(), LongType.LONG);
        builder.put(DoubleType.DOUBLE.getName(), DoubleType.DOUBLE);
        builder.put(StringType.STRING.getName(), StringType.STRING);
        builder.put(TimestampType.TIMESTAMP.getName(), TimestampType.TIMESTAMP);
        stringToTypeMap = builder.build();
    }

    public TypeDeserializer()
    {
        super(Type.class);
    }

    @Override
    protected Type _deserialize(String value, DeserializationContext context)
            throws IOException
    {
        Type t = stringToTypeMap.get(value);
        if (t == null) {
            throw new JsonMappingException(
                    String.format("Unknown type name '%s'. Supported types are: %s",
                        value,
                        Joiner.on(", ").join(stringToTypeMap.keySet())));
        }
        return t;
    }
}