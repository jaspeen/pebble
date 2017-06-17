/*******************************************************************************
 * This file is part of Pebble.
 * <p>
 * Copyright (c) 2014 by Mitchell Bösecke
 * <p>
 * For the full copyright and license information, please view the LICENSE
 * file that was distributed with this source code.
 ******************************************************************************/
package com.mitchellbosecke.pebble.node.expression;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.mitchellbosecke.pebble.error.AttributeNotFoundException;
import com.mitchellbosecke.pebble.error.PebbleException;
import com.mitchellbosecke.pebble.error.RootAttributeNotFoundException;
import com.mitchellbosecke.pebble.extension.DynamicAttributeProvider;
import com.mitchellbosecke.pebble.extension.NodeVisitor;
import com.mitchellbosecke.pebble.extension.ResolvedAttribute;
import com.mitchellbosecke.pebble.node.ArgumentsNode;
import com.mitchellbosecke.pebble.node.PositionalArgumentNode;
import com.mitchellbosecke.pebble.template.EvaluationContextImpl;
import com.mitchellbosecke.pebble.template.PebbleTemplateImpl;


/**
 * Used to get an attribute from an object. It will look up attributes in the
 * following order: map entry, array item, list item,
 * {@link DynamicAttributeProvider}, get method, is method, has method, public method,
 * public field.
 *
 * @author Mitchell
 */
public class GetAttributeExpression implements Expression<Object> {

    private final Expression<?> node;

    private final Expression<?> attributeNameExpression;

    private final ArgumentsNode args;

    private final String filename;

    private final int lineNumber;

    /**
     * Potentially cached on first evaluation.
     */
    private final ConcurrentHashMap<MemberCacheKey, Member> memberCache;

    public GetAttributeExpression(Expression<?> node, Expression<?> attributeNameExpression, String filename,
                                  int lineNumber) {
        this(node, attributeNameExpression, null, filename, lineNumber);
    }

    public GetAttributeExpression(Expression<?> node, Expression<?> attributeNameExpression, ArgumentsNode args,
                                  String filename, int lineNumber) {

        this.node = node;
        this.attributeNameExpression = attributeNameExpression;
        this.args = args;
        this.filename = filename;
        this.lineNumber = lineNumber;

        /*
         * I dont imagine that users will often give different types to the same
         * template so we will give this cache a pretty small initial capacity.
         */
        this.memberCache = new ConcurrentHashMap<>(2, 0.9f, 1);
    }

    @Override
    public Object evaluate(PebbleTemplateImpl self, EvaluationContextImpl context) throws PebbleException {
        final Object object = this.node.evaluate(self, context);
        final Object attributeNameValue = this.attributeNameExpression.evaluate(self, context);
        final String attributeName = String.valueOf(attributeNameValue);
        final Object[] argumentValues = this.getArgumentValues(self, context);


        if (object == null && context.isStrictVariables()) {
            if (this.node instanceof ContextVariableExpression) {
                final String rootPropertyName = ((ContextVariableExpression) this.node).getName();
                throw new RootAttributeNotFoundException(null, String.format(
                        "Root attribute [%s] does not exist or can not be accessed and strict variables is set to true.",
                        rootPropertyName), rootPropertyName, this.lineNumber, this.filename);
            } else {
                throw new RootAttributeNotFoundException(null,
                        "Attempt to get attribute of null object and strict variables is set to true.", attributeName, this.lineNumber, this.filename);
            }
        }
        
        if (object != null) {

            Optional<ResolvedAttribute> resolvedAttribute=Optional.empty();
            
            // check if the object is able to provide the attribute dynamically
            if (!resolvedAttribute.isPresent()) {
                if(object instanceof DynamicAttributeProvider) {
                    final DynamicAttributeProvider dynamicAttributeProvider = (DynamicAttributeProvider) object;
                    if(dynamicAttributeProvider.canProvideDynamicAttribute(attributeName)) {
                        resolvedAttribute=Optional.<ResolvedAttribute>of(new ResolvedAttribute() {
                            
                            @Override
                            public Object get() throws PebbleException {
                                return dynamicAttributeProvider.getDynamicAttribute(attributeNameValue, argumentValues);
                            }
                        });
                    }
                }
            }
            
            
            if (this.args == null) {
                /*
                 * If, and only if, no arguments were provided does it make sense to
                 * check maps/arrays/lists
                 */
                if (!resolvedAttribute.isPresent()) {
                    resolvedAttribute = resolveMap(object, attributeNameValue);
                }
    
                if (!resolvedAttribute.isPresent()) {
                    resolvedAttribute = resolveArray(object, attributeNameValue, context.isStrictVariables());
                }
    
                if (!resolvedAttribute.isPresent()) {
                    resolvedAttribute = resolveList(object, attributeNameValue, context.isStrictVariables());
                }
            }
            
            if (!resolvedAttribute.isPresent()) {
                resolvedAttribute = resolveMemberCall(object, attributeName, argumentValues);
            }
            
            if (resolvedAttribute.isPresent()) {
                return resolvedAttribute.get().get();
            } 
            
            if (context.isStrictVariables()) {
                throw new AttributeNotFoundException(null, String.format(
                        "Attribute [%s] of [%s] does not exist or can not be accessed and strict variables is set to true.",
                        attributeName, object.getClass().getName()), attributeName, this.lineNumber, this.filename);
            }
        }

        return null;

    }

    private Optional<ResolvedAttribute> resolveMemberCall(final Object object, String attributeName, final Object[] argumentValues) {
        
        final Member member = memberOf(object, attributeName, argumentValues);

        if (object != null && member != null) {
            return Optional.<ResolvedAttribute>of(new ResolvedAttribute() {
                
                @Override
                public Object get() throws PebbleException {
                    return invokeMember(object, member, argumentValues);
                }
            });
        }
        
        return Optional.empty();
    }

    private Member memberOf(final Object object, String attributeName, final Object[] argumentValues) {
        Member member = object == null ? null : this.memberCache.get(new MemberCacheKey(object.getClass(), attributeName));
        if (object != null && member == null) {

            /*
             * turn args into an array of types and an array of values in order
             * to use them for our reflection calls
             */
            Class<?>[] argumentTypes = new Class<?>[argumentValues.length];

            for (int i = 0; i < argumentValues.length; i++) {
                Object o = argumentValues[i];
                if (o == null) {
                    argumentTypes[i] = null;
                } else {
                    argumentTypes[i] = o.getClass();
                }
            }

            member = this.reflect(object, attributeName, argumentTypes);
            if (member != null) {
                this.memberCache.put(new MemberCacheKey(object.getClass(), attributeName), member);
            }

        }
        
        final Member resultingMember = member;
        return resultingMember;
    }

    private Optional<ResolvedAttribute> resolveList(Object object, Object attributeNameValue, boolean strictVariables) throws AttributeNotFoundException {
        // then lists
        if (object instanceof List) {
            String attributeName = String.valueOf(attributeNameValue);

            @SuppressWarnings("unchecked")
            final List<Object> list = (List<Object>) object;

            Optional<Integer> optIndex = asIndex(attributeName);
            if (optIndex.isPresent()) {
                final int index = optIndex.get();
                int length = list.size();
    
                if (index < 0 || index >= length) {
                    if (strictVariables) {
                        throw new AttributeNotFoundException(null,
                                "Index out of bounds while accessing array with strict variables on.",
                                attributeName, this.lineNumber, this.filename);
                    } else {
                        return Optional.<ResolvedAttribute>of(new ResolvedAttribute() {
                            @Override
                            public Object get() throws PebbleException {
                                return null;
                            }
                        });
                    }
                }
    
                return Optional.<ResolvedAttribute>of(new ResolvedAttribute() {
                    @Override
                    public Object get() throws PebbleException {
                        return list.get(index);
                    }
                });
            }
        }
        return Optional.empty();
    }

    private Optional<ResolvedAttribute> resolveArray(final Object object, Object attributeNameValue, boolean isStrictVariables) throws AttributeNotFoundException {
        if (object.getClass().isArray()) {
            String attributeName = String.valueOf(attributeNameValue);
            Optional<Integer> optIndex = asIndex(attributeName);
            if (optIndex.isPresent()) {
                final int index = optIndex.get();
                int length = Array.getLength(object);
                if (index < 0 || index >= length) {
                    if (isStrictVariables) {
                        throw new AttributeNotFoundException(null,
                                "Index out of bounds while accessing array with strict variables on.",
                                attributeName, this.lineNumber, this.filename);
                    } else {
                        return Optional.<ResolvedAttribute>of(new ResolvedAttribute() {
                            @Override
                            public Object get() throws PebbleException {
                                return null;
                            }
                        });
                    }
                }
                return Optional.<ResolvedAttribute>of(new ResolvedAttribute() {
                    @Override
                    public Object get() throws PebbleException {
                        return Array.get(object, index);
                    }
                });
            }
        }
        return Optional.empty();
    }

    private Optional<Integer> asIndex(String attributeName) {
        try {
            return Optional.of(Integer.parseInt(attributeName));
        } catch (NumberFormatException nx) {
            
        }
        return Optional.empty();
    }

    private Optional<ResolvedAttribute> resolveMap(final Object object, final Object attributeNameValue) {
        if (object instanceof Map) {
            return Optional.<ResolvedAttribute>of(new ResolvedAttribute() {
                
                @Override
                public Object get() throws PebbleException {
                    return getObjectFromMap((Map<?, ?>) object, attributeNameValue);
                }
            });
        }
        return Optional.empty();
    }

    private Object getObjectFromMap(Map<?, ?> object, Object attributeNameValue) throws PebbleException {
        if (object.isEmpty()) {
            return null;
        }
        if (Number.class.isAssignableFrom(attributeNameValue.getClass())) {
            Number keyAsNumber = (Number) attributeNameValue;

            Class<?> keyClass = object.keySet().iterator().next().getClass();
            Object key = this.cast(keyAsNumber, keyClass);
            return object.get(key);
        }
        return object.get(attributeNameValue);
    }

    private Object cast(Number number, Class<?> desiredType) throws PebbleException {
        if (desiredType == Long.class) {
            return number.longValue();
        } else if (desiredType == Integer.class) {
            return number.intValue();
        } else if (desiredType == Double.class) {
            return number.doubleValue();
        } else if (desiredType == Float.class) {
            return number.floatValue();
        } else if (desiredType == Short.class) {
            return number.shortValue();
        } else if (desiredType == Byte.class) {
            return number.byteValue();
        }
        throw new PebbleException(null, String.format("type %s not supported for key %s", desiredType, number), this.getLineNumber(), this.filename);
    }

    /**
     * Invoke the "Member" that was found via reflection.
     *
     * @param object
     * @param member
     * @param argumentValues
     * @return
     */
    private Object invokeMember(Object object, Member member, Object[] argumentValues) {
        Object result = null;
        try {
            if (member instanceof Method) {
                result = ((Method) member).invoke(object, argumentValues);
            } else if (member instanceof Field) {
                result = ((Field) member).get(object);
            }

        } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    /**
     * Fully evaluates the individual arguments.
     *
     * @param self
     * @param context
     * @return
     * @throws PebbleException
     */
    private Object[] getArgumentValues(PebbleTemplateImpl self, EvaluationContextImpl context) throws PebbleException {

        Object[] argumentValues;

        if (this.args == null) {
            argumentValues = new Object[0];
        } else {
            List<PositionalArgumentNode> args = this.args.getPositionalArgs();

            argumentValues = new Object[args.size()];

            int index = 0;
            for (PositionalArgumentNode arg : args) {
                Object argumentValue = arg.getValueExpression().evaluate(self, context);
                argumentValues[index] = argumentValue;
                index++;
            }
        }
        return argumentValues;
    }

    /**
     * Performs the actual reflection to obtain a "Member" from a class.
     *
     * @param object
     * @param attributeName
     * @param parameterTypes
     * @return
     */
    private Member reflect(Object object, String attributeName, Class<?>[] parameterTypes) {

        Class<?> clazz = object.getClass();

        Member result = null;

        // capitalize first letter of attribute for the following attempts
        String attributeCapitalized = Character.toUpperCase(attributeName.charAt(0)) + attributeName.substring(1);

        // check get method
        result = this.findMethod(clazz, "get" + attributeCapitalized, parameterTypes);

        // check is method
        if (result == null) {
            result = this.findMethod(clazz, "is" + attributeCapitalized, parameterTypes);
        }

        // check has method
        if (result == null) {
            result = this.findMethod(clazz, "has" + attributeCapitalized, parameterTypes);
        }

        // check if attribute is a public method
        if (result == null) {
            result = this.findMethod(clazz, attributeName, parameterTypes);
        }

        // public field
        if (result == null) {
            try {
                result = clazz.getField(attributeName);
            } catch (NoSuchFieldException | SecurityException e) {
            }
        }

        if (result != null) {
            ((AccessibleObject) result).setAccessible(true);
        }

        return result;
    }

    /**
     * Finds an appropriate method by comparing if parameter types are
     * compatible. This is more relaxed than class.getMethod.
     *
     * @param clazz
     * @param name
     * @param requiredTypes
     * @return
     */
    private Method findMethod(Class<?> clazz, String name, Class<?>[] requiredTypes) {
        Method result = null;

        Method[] candidates = clazz.getMethods();

        for (Method candidate : candidates) {
            if (!candidate.getName().equalsIgnoreCase(name)) {
                continue;
            }

            Class<?>[] types = candidate.getParameterTypes();

            if (types.length != requiredTypes.length) {
                continue;
            }

            boolean compatibleTypes = true;
            for (int i = 0; i < types.length; i++) {
                if (requiredTypes[i] != null && !this.widen(types[i]).isAssignableFrom(requiredTypes[i])) {
                    compatibleTypes = false;
                    break;
                }
            }

            if (compatibleTypes) {
                result = candidate;
                break;
            }
        }
        return result;
    }

    /**
     * Performs a widening conversion (primitive to boxed type)
     *
     * @param clazz
     * @return
     */
    private Class<?> widen(Class<?> clazz) {
        Class<?> result = clazz;
        if (clazz == int.class) {
            result = Integer.class;
        } else if (clazz == long.class) {
            result = Long.class;
        } else if (clazz == double.class) {
            result = Double.class;
        } else if (clazz == float.class) {
            result = Float.class;
        } else if (clazz == short.class) {
            result = Short.class;
        } else if (clazz == byte.class) {
            result = Byte.class;
        } else if (clazz == boolean.class) {
            result = Boolean.class;
        }
        return result;
    }

    private class MemberCacheKey {
        private final Class<?> clazz;
        private final String attributeName;

        private MemberCacheKey(Class<?> clazz, String attributeName) {
            this.clazz = clazz;
            this.attributeName = attributeName;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || this.getClass() != o.getClass()) return false;

            MemberCacheKey that = (MemberCacheKey) o;

            if (!this.clazz.equals(that.clazz)) return false;
            return this.attributeName.equals(that.attributeName);

        }

        @Override
        public int hashCode() {
            int result = this.clazz.hashCode();
            result = 31 * result + this.attributeName.hashCode();
            return result;
        }
    }

    @Override
    public void accept(NodeVisitor visitor) {
        visitor.visit(this);
    }

    public Expression<?> getNode() {
        return this.node;
    }

    public Expression<?> getAttributeNameExpression() {
        return this.attributeNameExpression;
    }

    public ArgumentsNode getArgumentsNode() {
        return this.args;
    }

    @Override
    public int getLineNumber() {
        return this.lineNumber;
    }

}
