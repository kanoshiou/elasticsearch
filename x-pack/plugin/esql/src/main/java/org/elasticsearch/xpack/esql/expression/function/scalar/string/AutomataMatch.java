/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.expression.function.scalar.string;

import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.automaton.Automaton;
import java.util.function.Function;
import org.apache.lucene.util.automaton.ByteRunAutomaton;
import org.apache.lucene.util.automaton.Operations;
import org.apache.lucene.util.automaton.TooComplexToDeterminizeException;
import org.apache.lucene.util.automaton.Transition;
import org.apache.lucene.util.automaton.UTF32ToUTF8;
import org.elasticsearch.common.breaker.CircuitBreaker;
import org.elasticsearch.compute.ann.Evaluator;
import org.elasticsearch.compute.ann.Fixed;
import org.elasticsearch.compute.data.BooleanBlock;
import org.elasticsearch.compute.expression.ExpressionEvaluator;
import org.elasticsearch.compute.operator.DriverContext;
import org.elasticsearch.lucene.util.automaton.CircuitBreakingOperations;
import org.elasticsearch.xpack.esql.core.tree.Source;

/**
 * Matches {@link BytesRef}s against {@link Automaton automata}.
 */
public class AutomataMatch {
    /**
     * Build an {@link ExpressionEvaluator.Factory} that will match
     * {@link BytesRef}s against {@link Automaton automata} and return a {@link BooleanBlock}.
     * <p>
     * Automaton determinization and {@link ByteRunAutomaton} construction are deferred to
     * {@link ExpressionEvaluator.Factory#get(DriverContext)} time so that a {@link CircuitBreaker}
     * is available to guard against pathological patterns that could OOM the JVM.
     */
    public static ExpressionEvaluator.Factory toEvaluator(Source source, ExpressionEvaluator.Factory field, Automaton utf32Automaton) {
        return toEvaluator(source, field, breaker -> utf32Automaton);
    }

    /**
     * Build an {@link ExpressionEvaluator.Factory} that will match
     * {@link BytesRef}s against {@link Automaton automata} and return a {@link BooleanBlock}.
     * <p>
     * The automaton is created lazily at {@link ExpressionEvaluator.Factory#get(DriverContext)} time
     * via the supplied {@code automatonFactory}, so that a {@link CircuitBreaker} is available to
     * guard against pathological patterns that could OOM the JVM.
     */
    public static ExpressionEvaluator.Factory toEvaluator(
        Source source,
        ExpressionEvaluator.Factory field,
        Function<CircuitBreaker, Automaton> automatonFactory
    ) {
        return new ExpressionEvaluator.Factory() {
            @Override
            public ExpressionEvaluator get(DriverContext context) {
                /*
                 * ByteRunAutomaton has a way to convert utf32 to utf8, but if we used it
                 * we couldn't get a nice toDot - so we call UTF32ToUTF8 ourselves.
                 */
                CircuitBreaker breaker = context.breaker();
                Automaton utf32Automaton = automatonFactory.apply(breaker);
                Automaton automaton;
                try {
                    automaton = CircuitBreakingOperations.determinize(
                        new UTF32ToUTF8().convert(utf32Automaton),
                        Operations.DEFAULT_DETERMINIZE_WORK_LIMIT,
                        breaker,
                        "esql-like-rlike"
                    );
                } catch (TooComplexToDeterminizeException e) {
                    throw new IllegalArgumentException("Pattern was too complex to determinize", e);
                }

                ByteRunAutomaton run = new ByteRunAutomaton(automaton, true);
                if (breaker != null) {
                    breaker.addEstimateBytesAndMaybeBreak(run.ramBytesUsed(), "esql-like-rlike-automaton");
                }
                return new AutomataMatchEvaluator(source, field.get(context), run, toDot(automaton), context);
            }

            @Override
            public String toString() {
                return "AutomataMatchEvaluator[input=" + field + ", automatonFactory=" + automatonFactory + "]";
            }
        };
    }

    @Evaluator
    static boolean process(BytesRef input, @Fixed(includeInToString = false) ByteRunAutomaton automaton, @Fixed String pattern) {
        if (input == null) {
            return false;
        }
        return automaton.run(input.bytes, input.offset, input.length);
    }

    private static final int MAX_LENGTH = 1024 * 64;

    /**
     * Convert an {@link Automaton} to <a href="https://graphviz.org/doc/info/lang.html">dot</a>.
     * <p>
     *  This was borrowed from {@link Automaton#toDot} but has been modified to snip if the length
     *  grows too much and to format the bytes differently.
     * </p>
     */
    public static String toDot(Automaton automaton) {
        StringBuilder b = new StringBuilder();
        b.append("digraph Automaton {\n");
        b.append("  rankdir = LR\n");
        b.append("  node [width=0.2, height=0.2, fontsize=8]\n");
        int numStates = automaton.getNumStates();
        if (numStates > 0) {
            b.append("  initial [shape=plaintext,label=\"\"]\n");
            b.append("  initial -> 0\n");
        }

        Transition t = new Transition();

        too_big: for (int state = 0; state < numStates; ++state) {
            b.append("  ");
            b.append(state);
            if (automaton.isAccept(state)) {
                b.append(" [shape=doublecircle,label=\"").append(state).append("\"]\n");
            } else {
                b.append(" [shape=circle,label=\"").append(state).append("\"]\n");
            }

            int numTransitions = automaton.initTransition(state, t);

            for (int i = 0; i < numTransitions; ++i) {
                automaton.getNextTransition(t);

                assert t.max >= t.min;

                b.append("  ");
                b.append(state);
                b.append(" -> ");
                b.append(t.dest);
                b.append(" [label=\"");
                appendByte(t.min, b);
                if (t.max != t.min) {
                    b.append('-');
                    appendByte(t.max, b);
                }

                b.append("\"]\n");
                if (b.length() >= MAX_LENGTH) {
                    b.append("...snip...");
                    break too_big;
                }
            }
        }

        b.append('}');
        return b.toString();
    }

    static void appendByte(int c, StringBuilder b) {
        if (c > 255) {
            throw new UnsupportedOperationException("can only format bytes but got [" + c + "]");
        }
        if (c == 34) {
            b.append("\\\"");
            return;
        }
        if (c == 92) {
            b.append("\\\\");
            return;
        }
        if (c >= 33 && c <= 126) {
            b.appendCodePoint(c);
            return;
        }
        b.append("0x");
        String hex = Integer.toHexString(c);
        switch (hex.length()) {
            case 1 -> b.append('0').append(hex);
            case 2 -> b.append(hex);
            default -> throw new UnsupportedOperationException("can only format bytes");
        }
    }
}
