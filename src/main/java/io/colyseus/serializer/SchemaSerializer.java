package io.colyseus.serializer;

import io.colyseus.serializer.schema.Iterator;
import io.colyseus.serializer.schema.Schema;

import java.lang.reflect.InvocationTargetException;

public class SchemaSerializer<T> {

    protected T state;

    public SchemaSerializer(Class<T> type) throws NoSuchMethodException, IllegalAccessException, InvocationTargetException, InstantiationException {
        this.state = type.getConstructor().newInstance();
    }

    public void setState(byte[] data) throws NoSuchMethodException, NoSuchFieldException, InstantiationException, IllegalAccessException, InvocationTargetException {
//        System.out.println("\n----------setState----------\n");
        ((Schema) state).decode(data);
    }

    public T getState() {
        return state;
    }

    public void patch(byte[] data) throws NoSuchMethodException, NoSuchFieldException, InstantiationException, IllegalAccessException, InvocationTargetException {
//        System.out.println("\n----------patch----------\n");
        ((Schema) state).decode(data);
    }

    public void handshake(byte[] bytes) throws InvocationTargetException, NoSuchMethodException, InstantiationException, IllegalAccessException, NoSuchFieldException {
        Schema.SchemaReflection reflection = new Schema.SchemaReflection();
        Iterator it = new Iterator(0);
        reflection.decode(bytes, it);
    }
}