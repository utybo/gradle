/*
 * Copyright 2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.messaging.remote.internal

import org.gradle.internal.exceptions.AbstractMultiCauseException
import spock.lang.Specification
import spock.lang.Issue
import spock.lang.Ignore

class MessageTest extends Specification {
    GroovyClassLoader source = new GroovyClassLoader(getClass().classLoader)
    GroovyClassLoader dest = new GroovyClassLoader(getClass().classLoader)

    def "can transport exception graph"() {
        def cause1 = new RuntimeException("nested-1")
        def cause2 = new IOException("nested-2")
        def cause = new AbstractMultiCauseException("nested", cause1, cause2)
        def original = new ExceptionWithExceptionField("message", cause)

        when:
        def transported = transport(new TestPayloadMessage(payload: original))

        then:
        transported.payload.class == ExceptionWithExceptionField
        transported.payload.message == "message"

        and:
        transported.payload.throwable.class == AbstractMultiCauseException
        transported.payload.throwable.message == "nested"

        and:
        transported.payload.throwable == transported.payload.cause

        and:
        transported.payload.throwable.causes.size() == 2
        transported.payload.throwable.causes*.class == [RuntimeException, IOException]
        transported.payload.throwable.causes*.message == ["nested-1", "nested-2"]
    }

    def "replaces exception with broken writeObject() method with placeholder"() {
        def cause = new RuntimeException("nested")
        def original = new BrokenWriteObjectException("message", cause)

        when:
        def transported = transport(original)

        then:
        looksLike original, transported

        and:
        transported.cause.class == RuntimeException.class
        transported.cause.message == "nested"
        transported.cause.stackTrace == cause.getStackTrace()
    }

    def "replaces exception with field that cannot be serialized with placeholder"() {
        def cause = new RuntimeException("nested")
        def original = new ExceptionWithNonSerializableField("message", cause)

        when:
        def transported = transport(original)

        then:
        looksLike original, transported

        and:
        transported.cause.class == RuntimeException.class
        transported.cause.message == "nested"
        transported.cause.stackTrace == cause.getStackTrace()
    }

    def "replaces nested unserializable exception with placeholder"() {
        def cause = new IOException("nested")
        def original = new BrokenWriteObjectException("message", cause)
        def outer = new RuntimeException("message", original)

        when:
        def transported = transport(outer)

        then:
        transported instanceof RuntimeException
        transported.class == RuntimeException.class
        transported.message == "message"
        transported.stackTrace == outer.stackTrace

        and:
        looksLike original, transported.cause

        and:
        transported.cause.cause.class == IOException
        transported.cause.cause.message == "nested"
        transported.cause.cause.stackTrace == cause.stackTrace
    }

    def "replaces undeserializable exception with placeholder"() {
        def cause = new RuntimeException("nested")
        def original = new BrokenReadObjectException("message", cause)

        when:
        def transported = transport(original)

        then:
        looksLike original, transported

        and:
        transported.cause.class == RuntimeException.class
        transported.cause.message == "nested"
        transported.cause.stackTrace == cause.stackTrace
    }

    def "replaces nested undeserializable exception with placeholder"() {
        def cause = new RuntimeException("nested")
        def original = new BrokenReadObjectException("message", cause)
        def outer = new RuntimeException("message", original)

        when:
        def transported = transport(outer)

        then:
        transported instanceof RuntimeException
        transported.class == RuntimeException
        transported.message == "message"
        transported.stackTrace == outer.stackTrace

        and:
        looksLike original, transported.cause

        and:
        transported.cause.cause.class == RuntimeException.class
        transported.cause.cause.message == "nested"
        transported.cause.cause.stackTrace == cause.stackTrace
    }

    def "replaces unserializable exception field with placeholder"() {
        def cause = new RuntimeException()
        def original = new BrokenReadObjectException("message", cause)
        def outer = new ExceptionWithExceptionField("nested", original)

        when:
        def transported = transport(outer)

        then:
        transported instanceof ExceptionWithExceptionField

        and:
        looksLike original, transported.throwable

        and:
        transported.throwable == transported.cause
    }

    def "replaces incompatible exception with local version"() {
        def cause = new RuntimeException("nested")
        def sourceExceptionType = source.parseClass(
                "package org.gradle; public class TestException extends RuntimeException { public TestException(String msg, Throwable cause) { super(msg, cause); } }")
        def destExceptionType = dest.parseClass(
                "package org.gradle; public class TestException extends RuntimeException { private String someField; public TestException(String msg) { super(msg); } }")

        def original = sourceExceptionType.newInstance("message", cause)

        when:
        def transported = transport(original)

        then:
        transported instanceof RuntimeException
        transported.class == destExceptionType
        transported.message == original.message
        transported.stackTrace == original.stackTrace

        transported.cause.class == RuntimeException.class
        transported.cause.message == "nested"
        transported.cause.stackTrace == cause.stackTrace
    }

    def "uses placeholder when local exception cannot be constructed"() {
        def cause = new RuntimeException("nested")
        def sourceExceptionType = source.parseClass(
                "package org.gradle; public class TestException extends RuntimeException { public TestException(String msg, Throwable cause) { super(msg, cause); } }")
        dest.parseClass("package org.gradle; public class TestException extends RuntimeException { private String someField; }")

        def original = sourceExceptionType.newInstance("message", cause)

        when:
        def transported = transport(original)

        then:
        looksLike original, transported

        and:
        transported.cause.class == RuntimeException.class
        transported.cause.message == "nested"
        transported.cause.stackTrace == cause.stackTrace
    }

    def "transports exception with broken methods"() {
        def broken = new CompletelyBrokenException()

        when:
        def transported = transport(broken)

        then:
        transported.class == CompletelyBrokenException
    }

    def "transports unserializable exception with broken methods"() {
        def broken = new CompletelyBrokenException() { def Object o = new Object() }

        when:
        def transported = transport(broken)

        then:
        transported.class == PlaceholderException
        transported.cause == null
        transported.stackTrace.length == 0

        when:
        transported.message

        then:
        RuntimeException e = thrown()
        e.message == 'broken getMessage()'

        when:
        transported.toString()

        then:
        RuntimeException e2 = thrown()
        e2.message == 'broken toString()'
    }

    @Ignore
    @Issue("GRADLE-1996")
    def "can transport exception that implements writeReplace()"() {
        def original = new WriteReplaceException("original")

        when:
        def transported = transport(original)

        then:
        noExceptionThrown()
        transported instanceof WriteReplaceException
        transported.message == "replaced"
    }

    void looksLike(Throwable original, Throwable transported) {
        assert transported instanceof PlaceholderException
        assert transported.exceptionClassName == original.class.name
        assert transported.message == original.message
        assert transported.toString() == original.toString()
        assert transported.stackTrace == original.stackTrace
    }

    private Object transport(Object arg) {
        def outputStream = new ByteArrayOutputStream()
        Message.send(new TestPayloadMessage(payload: arg), outputStream)

        def inputStream = new ByteArrayInputStream(outputStream.toByteArray())
        def message = Message.receive(inputStream, dest)
        return message.payload
    }

    static class TestPayloadMessage extends Message {
        def payload
    }

    static class ExceptionWithNonSerializableField extends RuntimeException {
        def canNotSerialize = new Object()

        ExceptionWithNonSerializableField(String message, Throwable cause) {
            super(message, cause)
        }
    }

    static class ExceptionWithExceptionField extends RuntimeException {
        Throwable throwable

        ExceptionWithExceptionField(String message, Throwable cause) {
            super(message, cause)
            throwable = cause
        }
    }

    static class BrokenWriteObjectException extends RuntimeException {
        BrokenWriteObjectException(String message, Throwable cause) {
            super(message, cause)
        }

        private void writeObject(ObjectOutputStream outstr) throws IOException {
            outstr.writeObject(new Object())
        }
    }

    static class UnserializableToStringException extends RuntimeException {
        UnserializableToStringException (String message, Throwable cause) {
            super(message, cause)
        }

        public String toString() {
            throw new BrokenWriteObjectException("broken toString", null);
        }

        private void writeObject(ObjectOutputStream outstr) throws IOException {
            outstr.writeObject(new Object())
        }
    }

    static class CompletelyBrokenException extends RuntimeException {
        @Override
        String getMessage() {
            throw new RuntimeException("broken getMessage()", null);
        }

        @Override
        public String toString() {
            throw new RuntimeException("broken toString()", null);
        }

        @Override
        public Throwable getCause() {
            throw new RuntimeException("broken getCause()", null);
        }

        @Override
        StackTraceElement[] getStackTrace() {
            throw new RuntimeException("broken getStackTrace()", null);
        }
    }

    static class BrokenToStringException extends RuntimeException {
        BrokenToStringException(String message, Throwable cause) {
            super(message, cause)
        }

        public String toString() {
            throw new RuntimeException("broken toString", null);
        }
    }

    static class BrokenReadObjectException extends RuntimeException {
        BrokenReadObjectException(String message, Throwable cause) {
            super(message, cause)
        }

        private void readObject(ObjectInputStream outstr)  {
            throw new RuntimeException("broken readObject()")
        }
    }

    static class WriteReplaceException extends Exception {
        WriteReplaceException(String message) {
            super(message)
        }

        private Object writeReplace() {
            return new WriteReplaceException("replaced")
        }
    }
}

