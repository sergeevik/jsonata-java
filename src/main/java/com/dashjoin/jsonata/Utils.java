/**
 * jsonata-java is the JSONata Java reference port
 * 
 * Copyright Dashjoin GmbH. https://dashjoin.com
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dashjoin.jsonata;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.dashjoin.jsonata.Jsonata.JFunction;
import com.dashjoin.jsonata.Jsonata.JFunctionCallable;

@SuppressWarnings({"rawtypes"})
public class Utils {
    public static boolean isNumeric(Object v) {
        if (v instanceof Long) return true;
        boolean isNum = false;
        if (v instanceof Number) {
            double d = ((Number)v).doubleValue();
            isNum = !Double.isNaN(d);
            if (isNum && !Double.isFinite(d)) {
                throw new JException("D1001", 0, v);
            }
        }
        return isNum;
    }

    public static boolean isArrayOfStrings(Object v) {
        boolean result = false;
        if (v instanceof Collection) {
            for (Object o : ((Collection)v))
                if (!(o instanceof String))
                    return false;
            return true;
        }
        return false;
    }
    public static boolean isArrayOfNumbers(Object v) {
        boolean result = false;
        if (v instanceof Collection) {
            for (Object o : ((Collection)v))
                if (!isNumeric(o))
                    return false;
            return true;
        }
        return false;
    }

    public static boolean isFunction(Object o) {
        return o instanceof JFunction || o instanceof JFunctionCallable;
    }

    static Object NONE = new Object();

    /**
     * Create an empty sequence to contain query results
     * @returns {Array} - empty sequence
     */
    public static List<Object> createSequence() { return createSequence(NONE); }

    public static List<Object> createSequence(Object el) {
        if (el==NONE) {
            JList<Object> sequence = new JList<>();
            sequence.sequence = true;
            return sequence;
        }
        // singleton sequences (wrapped inputs / values) usually stay singletons:
        // avoid the default capacity of 10
        JList<Object> sequence = new JList<>(1);
        sequence.sequence = true;
        sequence.add(el);
        return sequence;
    }

    public static class JList<E> extends ArrayList<E> {
        public JList() { super(); }
        public JList(int capacity) { super(capacity); }
        public JList(Collection<? extends E> c) {
            super(c);
        }

        // Jsonata specific flags
        public boolean sequence;

        public boolean outerWrapper;

        public boolean tupleStream;

        public boolean keepSingleton;

        public boolean cons;
    }

    public static boolean isSequence(Object result) {
        return result instanceof JList && ((JList)result).sequence;
    }

    /**
     * List representing an int range [a,b]
     * Both sides are included. Read-only + immutable.
     * 
     * Used for late materialization of ranges.
     */
    public static class RangeList extends AbstractList<Number> {

        final long a, b;
        final int size;

        public RangeList(long left, long right) {
            assert(left<=right);
            assert(right-left < Integer.MAX_VALUE);
            a = left; b = right;
            size = (int) (b-a+1);
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public boolean addAll(Collection<? extends Number> c) {
            throw new UnsupportedOperationException("RangeList does not support 'addAll'");
        }

        @Override
        public Number get(int index) {
            if (index < size) {
                return Utils.convertNumber( a + index );
            }
            throw new IndexOutOfBoundsException(index);
        }
    }

    public static Number convertNumber(Number n) {
        // Use long if the number is not fractional
        if (!isNumeric(n)) return null;
        if (n.longValue()==n.doubleValue()) {
            long l = n.longValue();
            if (((int)l)==l)
                return (int)l;
            else
                return l;
        }
        return n.doubleValue();
    }

    /**
     * Same as {@link #convertNumber(Number)} for a primitive double,
     * avoids boxing the intermediate result of arithmetic operations.
     */
    public static Number convertNumber(double d) {
        if (Double.isNaN(d)) return null;
        if (Double.isInfinite(d)) throw new JException("D1001", 0, d);
        long l = (long) d;
        if (l == d) {
            if (((int)l)==l)
                return (int)l;
            else
                return l;
        }
        return d;
    }

    /**
     * Same as {@link #convertNumber(Number)} for a primitive long (exact, no detour via double)
     */
    public static Number convertNumber(long l) {
        if (((int)l)==l)
            return (int)l;
        return l;
    }

    /**
     * Accumulates values with the semantics of repeated {@code value = Functions.append(value, v)} calls.
     * Functions.append always copies its first argument, so appending n items in a loop is O(n^2).
     * Once the accumulator holds a list created by append (i.e. a private copy nobody else references),
     * further items are appended in place.
     */
    static class Appender {
        Object value;
        boolean owned;

        Appender(Object initial) {
            value = initial;
        }

        void append(Object v) {
            if (owned) {
                if (v == null)
                    return;
                JList acc = (JList) value;
                if (acc.isEmpty() && v instanceof RangeList) {
                    // Functions.append shortcut: returns the range itself
                    value = v;
                    owned = false;
                } else if (v instanceof List) {
                    acc.addAll((List) v);
                } else {
                    acc.add(v);
                }
            } else {
                Object prev = value;
                value = Functions.append(prev, v);
                // append returns one of its arguments, or a fresh copy
                owned = value != prev && value != v;
            }
        }
    }

    public static void checkUrl(String str) {
      boolean isHigh = false;
      for ( int i=0; i<str.length(); i++) {
        boolean wasHigh = isHigh;
        isHigh = Character.isHighSurrogate(str.charAt(i));
        if (wasHigh && isHigh)
          throw new JException("Malformed URL", i);
      }
      if (isHigh)
        throw new JException("Malformed URL", 0);
    }

    static Object convertValue(Object val) {
        return val != Jsonata.NULL_VALUE ? val : null;
    }

    static void convertNulls(Map<String, Object> res) {
        for (Entry<String, Object> e : res.entrySet()) {
            Object val = e.getValue();
            Object l = convertValue(val);
            if (l!=val)
                e.setValue(l);
            recurse(val);
        }
    }

    static void convertNulls(List<Object> res) {
        for (int i=0; i<res.size(); i++) {
            Object val = res.get(i);
            Object l = convertValue(val);
            if (l!=val)
                res.set(i, l);
            recurse(val);
        }
    }

    static void recurse(Object val) {
        if (val instanceof Map)
            convertNulls((Map)val);
        if (val instanceof List)
            convertNulls((List)val);
    }

    /**
     * Converts all occurrences of {@link Jsonata#NULL_VALUE} in the given object to null
     * 
     * @param res Result object which might contain NULL_VALUEs
     * @return The same object with NULL_VALUEs replaced with null
     */
    public static Object convertNulls(Object res) {
        recurse(res);
        return convertValue(res);
    }
    
    /**
     * adapted from org.json.JSONObject https://github.com/stleary/JSON-java
     */
    public static void quote(String string, StringBuilder w) {
      char b;
      char c = 0;
      String hhhh;
      int i;
      int len = string.length();

      for (i = 0; i < len; i += 1) {
          b = c;
          c = string.charAt(i);
          switch (c) {
          case '\\':
          case '"':
              w.append('\\');
              w.append(c);
              break;
          /*
          case '/':
              if (b == '<') {
                  w.append('\\');
              }
              w.append(c);
              break;
          */
          case '\b':
              w.append("\\b");
              break;
          case '\t':
              w.append("\\t");
              break;
          case '\n':
              w.append("\\n");
              break;
          case '\f':
              w.append("\\f");
              break;
          case '\r':
              w.append("\\r");
              break;
          default:
              if (c < ' ' || (c >= '\u0080' && c < '\u00a0')
                      || (c >= '\u2000' && c < '\u2100')) {
                  w.append("\\u");
                  hhhh = Integer.toHexString(c);
                  w.append("0000", 0, 4 - hhhh.length());
                  w.append(hhhh);
              } else {
                  w.append(c);
              }
          }
      }
    }

    /**
     * version of Map.of(key, value) that allows value to be null
     */
    static Map mapOf(String key, Object value) {
        Map<String, Object> map = new HashMap<>();
        map.put(key, value);
        return map;
    }
    
    /**
     * called from Jsonata.compare() - aa and bb are either both strings or numbers
     * for numbers, compare doubleValue to avoid cast error when int is compared to double
     */
    @SuppressWarnings("unchecked")
    static int compareTo(Object aa, Object bb) {
        if (aa instanceof String)
            return ((Comparable)aa).compareTo(bb);
        else
            return Double.compare(((Number)aa).doubleValue(), ((Number)bb).doubleValue());
    }
}
