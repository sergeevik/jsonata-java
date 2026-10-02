package com.dashjoin.jsonata;

import static com.dashjoin.jsonata.Jsonata.jsonata;
import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.dashjoin.jsonata.json.Json;

/**
 * Covers the semantics of the performance optimizations (and the related thread safety fixes)
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class OptimizationTest {

    /**
     * The direct deep copy must produce the same result as the former JSON round trip
     */
    @Test
    public void testCloneSameAsJsonRoundTrip() {
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("int", 5);
        obj.put("long", 9007199254740993L);
        obj.put("smallLong", 7L);
        obj.put("double", 0.1 + 0.2);
        obj.put("integralDouble", 3.0);
        obj.put("big", 1e21);
        obj.put("tiny", 1.5e-7);
        obj.put("bigDecimal", new BigDecimal("1.25"));
        obj.put("bool", true);
        obj.put("str", "a\"b\\c\n ");
        obj.put("null", null);
        obj.put("jsonNull", Jsonata.NULL_VALUE);
        obj.put("list", new ArrayList<>(Arrays.asList(1, null, Jsonata.NULL_VALUE, "x", 2.5, new LinkedHashMap<>())));
        obj.put("nested", new LinkedHashMap<>(Map.of("a", List.of(List.of(1.0), Map.of()))));
        obj.put("fn", Jsonata.function("f", (Jsonata.Fn0<Object>) () -> 1, "<:n>"));

        Object expected = Json.parseJson(Functions.string(obj, false));
        Object actual = Functions.functionClone(obj);
        assertEquals(expected, actual);
        assertEquals(Functions.string(expected, false), Functions.string(actual, false));
    }

    @Test
    public void testGroupByManyItemsPerKey() {
        List<Object> items = new ArrayList<>();
        for (int i = 0; i < 1000; i++)
            items.add(Map.of("k", "k" + (i % 3), "v", i));
        Map res = (Map) jsonata("${k: v}").evaluate(items);
        assertEquals(3, res.size());
        assertEquals(334, ((List) res.get("k0")).size());
        assertEquals(333, ((List) res.get("k2")).size());
        assertEquals(Arrays.asList(1, 4, 7), ((List) res.get("k1")).subList(0, 3));
    }

    @Test
    public void testArrayConstructorAppend() {
        assertEquals(Arrays.asList(1, List.of(2), 3, 4, 5, 6, 7), jsonata("[1, [2], 3, $, 7]").evaluate(Arrays.asList(4, 5, 6)));
        assertEquals(Arrays.asList(1, 2, 3, 9), jsonata("[1..3, 9]").evaluate(null));
    }

    /**
     * The sort key is evaluated once per element, not twice per comparison
     */
    @Test
    public void testSortKeyEvaluatedOncePerElement() {
        AtomicInteger calls = new AtomicInteger();
        Jsonata expr = jsonata("$^($key($))");
        expr.registerFunction("key", (Jsonata.Fn1<Object, Object>) x -> {
            calls.incrementAndGet();
            return x;
        });
        List<Object> input = new ArrayList<>();
        for (int i = 0; i < 200; i++)
            input.add((i * 37) % 200);
        List res = (List) expr.evaluate(input);
        assertEquals(200, res.size());
        for (int i = 0; i < 200; i++)
            assertEquals(i, res.get(i));
        assertEquals(200, calls.get());
    }

    @Test
    public void testSortMultipleTermsStable() {
        Object input = Json.parseJson("[{\"a\":2,\"b\":\"x\",\"i\":0},{\"a\":1,\"b\":\"y\",\"i\":1},{\"a\":2,\"b\":\"a\",\"i\":2},{\"a\":1,\"b\":\"y\",\"i\":3}]");
        // a ascending, then b descending; i=1 and i=3 are equal: original order is kept
        assertEquals(Arrays.asList(1, 3, 0, 2), jsonata("$^(a, >b).i").evaluate(input));
    }

    /**
     * $sort with a comparator function: formerly List.sort() with a non-Comparator
     * ("Comparison method violates its general contract!" for larger arrays with duplicates)
     */
    @Test
    public void testSortFunctionStableMergeSort() {
        java.util.Random rnd = new java.util.Random(1);
        List<Object> data = new ArrayList<>();
        for (int i = 0; i < 300; i++)
            data.add(Map.of("k", rnd.nextInt(5), "i", i));
        List res = (List) jsonata("$sort($, function($a, $b){ $a.k > $b.k })").evaluate(data);
        assertEquals(300, res.size());
        for (int i = 1; i < res.size(); i++) {
            Map prev = (Map) res.get(i - 1), cur = (Map) res.get(i);
            int pk = (Integer) prev.get("k"), ck = (Integer) cur.get("k");
            assertTrue(pk < ck || (pk == ck && (Integer) prev.get("i") < (Integer) cur.get("i")), "sorted and stable at " + i);
        }
    }

    /**
     * $$ must refer to the input of the own evaluation, also when the same compiled
     * expression is evaluated concurrently without bindings
     */
    @Test
    public void testRootContextConcurrent() throws Exception {
        Jsonata expr = jsonata("items.($$.id & '-' & $)");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            final String id = "t" + t;
            futures.add(pool.submit(() -> {
                int errors = 0;
                for (int i = 0; i < 2000; i++) {
                    Object res = expr.evaluate(Map.of("id", id, "items", List.of("a", "b")));
                    if (!Arrays.asList(id + "-a", id + "-b").equals(res))
                        errors++;
                }
                return errors;
            }));
        }
        for (Future<Integer> f : futures)
            assertEquals(0, f.get());
        pool.shutdown();
    }

    /**
     * $millis() must return the timestamp of the current evaluation, also on threads other than the
     * one that compiled the expression
     */
    @Test
    public void testMillisOnOtherThread() throws Exception {
        Jsonata expr = jsonata("$millis()");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        long r1 = pool.submit(() -> ((Number) expr.evaluate(null)).longValue()).get();
        Thread.sleep(20);
        long r2 = pool.submit(() -> ((Number) expr.evaluate(null)).longValue()).get();
        pool.shutdown();
        assertTrue(r2 > r1, "$millis() should advance across separate evaluations");
    }

    /**
     * The evaluation context must not stay reachable from the thread after evaluation
     */
    @Test
    public void testNoThreadLocalRetention() {
        jsonata("$eval('a')").evaluate(Map.of("a", 1));
        assertNull(Jsonata.current.get());
    }

    /**
     * Variables bound at the top level of an expression are local to the evaluation
     * (formerly they were bound into the shared environment of the compiled expression)
     */
    @Test
    public void testTopLevelBindingNotShared() {
        Jsonata expr = jsonata("$x := ($exists($x) ? $x : 0) + 1");
        assertEquals(1, expr.evaluate(null));
        assertEquals(1, expr.evaluate(null));
    }
}
