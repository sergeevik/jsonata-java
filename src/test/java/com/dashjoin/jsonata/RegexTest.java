package com.dashjoin.jsonata;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

public class RegexTest {
    @Test
    public void testRegex() {
        var expression = Jsonata.jsonata("/^test.*$/");
        Object evaluate = expression.evaluate(null);
        String expected = "^test.*$";
        Assertions.assertEquals(expected, evaluate.toString());
    }

    @Test
    public void testEvalRegex() {
        var expression = Jsonata.jsonata("$eval('/^test.*$/')");
        Object evaluate = expression.evaluate(null);
        String expected = "^test.*$";
        Assertions.assertEquals(expected, evaluate.toString());
    }

    @Test
    public void testEvalRegex2() {

        Object data = Map.of(
                "domain1.test.data", Map.of(),
                "domain2.test.data", Map.of()
        );
        var expression = Jsonata.jsonata(
                "(\n" +
                        "    $matcher := $eval('/^(domain1)\\\\./i');\n" +
                        "    ('domain1.test.data' ~> $matcher)[0].match;\n" +
                        ")"
        );
        Object evaluate = expression.evaluate(data);
        String expected = "domain1.";
        Assertions.assertEquals(expected, evaluate);
    }

    @Test
    public void testEvalRegex3() {

        Object data = Map.of(
                "domain1.test.data", Map.of(),
                "domain2.test.data", Map.of()
        );
        var expression = Jsonata.jsonata(
                "(\n" +
                        "    $matcher := $eval('/^(domain1)\\\\./i');\n" +
                        "    'domain1.test.data' ~> $matcher" +
                        ")"
        );
        // TODO: why there evaluate return list but next test return map (object). In js always return object
        //    maybe problem in braces, but on try.jsonata always return object
        Map<String, Object> evaluate = (Map<String, Object>)(((List)expression.evaluate(data)).get(0));
        Assertions.assertEquals("domain1.", evaluate.get("match"));
        Assertions.assertEquals(0, evaluate.get("start"));
        Assertions.assertEquals(8, evaluate.get("end"));
        Assertions.assertEquals(List.of("domain1."), evaluate.get("groups"));
        Assertions.assertInstanceOf(Jsonata.Fn0.class, evaluate.get("next"));
    }

    @Test
    public void testEvalRegexNext() {

        Object data = Map.of(
                "domain1.test.data", Map.of(),
                "domain2.test.data", Map.of()
        );
        var expression = Jsonata.jsonata(
                "(\n" +
                        "    $matcher := $eval('/.(domain)./i');\n" +
                        "    ('domain1.test.domain.data.domain.a' ~> $matcher).next();\n" +
                        ")"
        );
        Map<String, Object> evaluate = (Map<String, Object>)(expression.evaluate(data));
        Assertions.assertEquals(".domain.", evaluate.get("match"));
        Assertions.assertEquals(24, evaluate.get("start"));
        Assertions.assertEquals(32, evaluate.get("end"));
        Assertions.assertEquals(List.of(".domain."), evaluate.get("groups"));
        Assertions.assertInstanceOf(Jsonata.Fn0.class, evaluate.get("next"));
    }
}
