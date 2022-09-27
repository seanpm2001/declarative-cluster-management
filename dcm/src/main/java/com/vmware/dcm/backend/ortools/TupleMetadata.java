/*
 * Copyright 2018-2020 VMware, Inc. All Rights Reserved.
 * SPDX-License-Identifier: BSD-2
 */

package com.vmware.dcm.backend.ortools;

import com.google.common.base.Preconditions;
import com.vmware.dcm.ModelException;
import com.vmware.dcm.compiler.IRColumn;
import com.vmware.dcm.compiler.IRTable;
import com.vmware.dcm.compiler.ir.BinaryOperatorPredicate;
import com.vmware.dcm.compiler.ir.ColumnIdentifier;
import com.vmware.dcm.compiler.ir.ExistsPredicate;
import com.vmware.dcm.compiler.ir.Expr;
import com.vmware.dcm.compiler.ir.FunctionCall;
import com.vmware.dcm.compiler.ir.GroupByComprehension;
import com.vmware.dcm.compiler.ir.Head;
import com.vmware.dcm.compiler.ir.IRVisitor;
import com.vmware.dcm.compiler.ir.IsNotNullPredicate;
import com.vmware.dcm.compiler.ir.IsNullPredicate;
import com.vmware.dcm.compiler.ir.ListComprehension;
import com.vmware.dcm.compiler.ir.Literal;
import com.vmware.dcm.compiler.ir.UnaryOperator;
import com.vmware.dcm.compiler.ir.VoidType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Tracks metadata about tuples. Specifically, it tracks the tuple types for tables, views and group by tables,
 * and resolves them into their corresponding Java types. For example, a table with an integer and a variable
 * column will have the Java parameter type recorded as a String, "Integer, IntVar".
 * For tuples created by views we create, it also tracks the indices of fields within the tuple.
 *
 * TODO: we likely don't need the split between tables/views here
 */
public class TupleMetadata {
    private static final Logger LOG = LoggerFactory.getLogger(TupleMetadata.class);
    private final Map<String, Map<String, JavaType>> tableToFieldToType = new HashMap<>();
    private final Map<String, Map<String, JavaType>> viewTupleTypeParameters = new HashMap<>();
    private final Map<String, Map<String, Integer>> tableToFieldIndex = new HashMap<>();
    private final Map<String, Map<String, Integer>> viewToFieldIndex = new HashMap<>();
    private final InferType inferType = new InferType();


    /**
     * For tables, compute the tuple types and field indices.
     *
     * @param table the IRTable entry for which
     */
    JavaTypeList recordTableTupleType(final IRTable table) {
        Preconditions.checkArgument(!tableToFieldToType.containsKey(table.getAliasedName()));
        Preconditions.checkArgument(!tableToFieldIndex.containsKey(table.getAliasedName()));
        final AtomicInteger fieldIndex = new AtomicInteger(0);
        return new JavaTypeList(table.getIRColumns().entrySet().stream()
                .map(e -> {
                        final JavaType retVal = inferType.typeFromColumn(e.getValue());
                        tableToFieldToType.computeIfAbsent(table.getAliasedName(), (k) -> new HashMap<>())
                                          .putIfAbsent(e.getKey(), retVal);
                        tableToFieldIndex.computeIfAbsent(table.getAliasedName(),  (k) -> new HashMap<>())
                                          .putIfAbsent(e.getKey(), fieldIndex.getAndIncrement());
                        return retVal;
                    }
                ).collect(Collectors.toList()));
    }

    /**
     * For intermediate views, compute the tuple types and field indices.
     *
     * @param viewName the view within which this expression is being visited
     * @param exprs The expressions to create a field for
     */
    <T extends Expr> JavaTypeList recordViewTupleType(final String viewName, final List<T> exprs) {
        final String upperCased = viewName.toUpperCase(Locale.US);
        Preconditions.checkArgument(!viewToFieldIndex.containsKey(upperCased));
        Preconditions.checkArgument(!viewTupleTypeParameters.containsKey(upperCased));
        final AtomicInteger fieldIndex = new AtomicInteger(0);
        final List<JavaType> typeList = exprs.stream().map(argument -> {
            final String fieldName = argument.getAlias().orElseGet(() -> {
                        if (argument instanceof ColumnIdentifier) {
                            return ((ColumnIdentifier) argument).getField().getName();
                        }
                        throw new ModelException("Non-column fields need an alias: " + argument);
                    }
            ).toUpperCase(Locale.US);
            final JavaType retVal = inferType(argument);
            viewToFieldIndex.computeIfAbsent(upperCased, (k) -> new HashMap<>())
                            .compute(fieldName, (k, v) -> fieldIndex.getAndIncrement());
            viewTupleTypeParameters.computeIfAbsent(upperCased, (k) -> new HashMap<>())
                            .compute(fieldName, (k, v) -> retVal);
            return retVal;
        }).collect(Collectors.toList());
        return new JavaTypeList(typeList);
    }

    JavaTypeList computeTupleGenericParameters(final List<Expr> exprs) {
        return new JavaTypeList(exprs.stream().map(this::inferType).collect(Collectors.toList()));
    }

    JavaType getTypeForField(final IRTable table, final IRColumn column) {
        return Objects.requireNonNull(tableToFieldToType.get(table.getName()).get(column.getName()));
    }

    JavaType getTypeForField(final String tableName, final String columnName) {
        return Objects.requireNonNull(tableToFieldToType.get(tableName).get(columnName));
    }

    int getFieldIndexInTable(final String tableName, final String columnName) {
        return Objects.requireNonNull(tableToFieldIndex.get(tableName).get(columnName));
    }

    int getFieldIndexInView(final String tableName, final String columnName) {
        return Objects.requireNonNull(viewToFieldIndex.get(tableName).get(columnName));
    }

    boolean isView(final String tableName) {
        return viewToFieldIndex.containsKey(tableName);
    }

    JavaType inferType(final IRColumn column) {
        return inferType.typeFromColumn(column);
    }

    JavaType inferType(final Expr expr) {
        return inferType.visit(expr);
    }

    private class InferType extends IRVisitor<JavaType, VoidType> {
        public JavaType visit(final Expr expr) {
            return super.visit(expr, VoidType.getAbsent());
        }

        @Override
        protected JavaType visitBinaryOperatorPredicate(final BinaryOperatorPredicate node, final VoidType context) {
            final JavaType leftType = visit(node.getLeft());
            final JavaType rightType = visit(node.getRight());
            final boolean isVar = JavaType.isVar(leftType) || JavaType.isVar(rightType);
            switch (node.getOperator()) {
                case EQUAL:
                case NOT_EQUAL:
                case LESS_THAN:
                case LESS_THAN_OR_EQUAL:
                case GREATER_THAN:
                case GREATER_THAN_OR_EQUAL:
                case IN:
                case OR:
                case AND:
                case CONTAINS:
                    return isVar ? JavaType.BoolVar : JavaType.Boolean;
                case ADD:
                case SUBTRACT:
                case MULTIPLY:
                case DIVIDE:
                case MODULUS:
                    return isVar ? JavaType.IntVar : JavaType.Integer;
                default:
                    throw new UnsupportedOperationException();
            }
        }

        @Override
        protected JavaType visitGroupByComprehension(final GroupByComprehension node, final VoidType context) {
            final Head head = node.getComprehension().getHead();
            if (head.getSelectExprs().size() == 1) {
                final JavaType type = visit(head.getSelectExprs().get(0), context);
                LOG.warn("Returning type of sub-query {} as {}", node, type);
                return type;
            }
            throw new UnsupportedOperationException("Do not know type of subquery");
        }

        @Override
        protected JavaType visitListComprehension(final ListComprehension node, final VoidType context) {
            final Head head = node.getHead();
            if (head.getSelectExprs().size() == 1) {
                final JavaType type = visit(head.getSelectExprs().get(0), context);
                LOG.warn("Returning type of sub-query {} as {}", node, type);
                return type;
            }
            throw new UnsupportedOperationException("Do not know type of subquery");
        }

        @Override
        protected JavaType visitColumnIdentifier(final ColumnIdentifier node, final VoidType context) {
            return typeFromColumn(node);
        }

        @Override
        protected JavaType visitFunctionCall(final FunctionCall node, final VoidType context) {
            return functionType(node);
        }

        @Override
        protected JavaType visitExistsPredicate(final ExistsPredicate node, final VoidType context) {
            // TODO: This is incomplete. It can be boolean if node.getArgument() is const.
            return JavaType.isVar(visit(node.getArgument(), context)) ? JavaType.BoolVar : JavaType.Boolean;
        }

        @Override
        protected JavaType visitIsNullPredicate(final IsNullPredicate node, final VoidType context) {
            return JavaType.isVar(visit(node.getArgument(), context)) ? JavaType.BoolVar : JavaType.Boolean;
        }

        @Override
        protected JavaType visitIsNotNullPredicate(final IsNotNullPredicate node, final VoidType context) {
            return JavaType.isVar(visit(node.getArgument(), context)) ? JavaType.BoolVar : JavaType.Boolean;
        }

        @Override
        protected JavaType visitUnaryOperator(final UnaryOperator node, final VoidType context) {
            final JavaType type = visit(node.getArgument(), context);
            switch (node.getOperator()) {
                case NOT:
                    return type == JavaType.BoolVar ? JavaType.BoolVar : JavaType.Boolean;
                case MINUS:
                case PLUS:
                    return type;
                default:
                    throw new IllegalArgumentException(node.toString());
            }
        }

        @Override
        protected JavaType visitLiteral(final Literal node, final VoidType context) {
            if (node.getValue() instanceof String) {
                return JavaType.String;
            } else if (node.getValue() instanceof Integer) {
                return JavaType.Integer;
            } else if (node.getValue() instanceof Boolean) {
                return JavaType.Boolean;
            } else if (node.getValue() instanceof Long) {
                return JavaType.Long;
            }
            return super.visitLiteral(node, context);
        }

        private JavaType typeFromColumn(final ColumnIdentifier node) {
            if (node.getField().isControllable()) {
                return JavaType.IntVar;
            }
            if (viewTupleTypeParameters.containsKey(node.getTableName())) {
                final JavaType type = viewTupleTypeParameters.get(node.getTableName()).get(node.getField().getName());
                return type;
            }
            return typeFromColumn(node.getField());
        }

        JavaType typeFromColumn(final IRColumn column) {
            switch (column.getType()) {
                case STRING:
                    return JavaType.String;
                case BOOL:
                    return JavaType.Boolean;
                case LONG:
                    return JavaType.Long;
                case INT:
                    return JavaType.Integer;
                case FLOAT:
                    return JavaType.Float;
                case ARRAY:
                    return JavaType.ObjectArray;
                default:
                    throw new IllegalArgumentException(column.toString());
            }
        }

        private JavaType functionType(final FunctionCall node) {
            if (node.getArgument().size() == 1) {
                final JavaType argumentType = visit(node.getArgument().get(0));
                switch (node.getFunction()) {
                    case SUM:
                    case COUNT:
                        return JavaType.isVar(argumentType) ? JavaType.IntVar : JavaType.Long;
                    case MAX:
                    case MIN:
                        return argumentType;
                    case ANY:
                    case ALL:
                    case ALL_EQUAL:
                    case ALL_DIFFERENT:
                    case INCREASING:
                        return JavaType.isVar(argumentType) ? JavaType.BoolVar : JavaType.Boolean;
                    default:
                        throw new IllegalArgumentException(node + " " + argumentType);
                }
            } else if (node.getArgument().size() == 2) {
                switch (node.getFunction()) {
                    case SCALAR_PRODUCT:
                        return JavaType.IntVar;
                    default:
                        throw new IllegalArgumentException(node.toString());
                }
            } else if (node.getArgument().size() == 4) {
                switch (node.getFunction()) {
                    case CAPACITY_CONSTRAINT:
                        return JavaType.BoolVar;
                    default:
                        throw new IllegalArgumentException(node.toString());
                }
            }
            throw new IllegalArgumentException("Unexpected function type " + node);
        }
    }
}