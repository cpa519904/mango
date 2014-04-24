/*
 * Copyright 2014 mango.concurrent.cc
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package cc.concurrent.mango.runtime.operator;

import cc.concurrent.mango.exception.IncorrectParameterCountException;
import cc.concurrent.mango.exception.IncorrectParameterTypeException;
import cc.concurrent.mango.exception.IncorrectSqlException;
import cc.concurrent.mango.runtime.ParsedSql;
import cc.concurrent.mango.runtime.RuntimeContext;
import cc.concurrent.mango.runtime.TypeContext;
import cc.concurrent.mango.runtime.parser.ASTIterableParameter;
import cc.concurrent.mango.runtime.parser.ASTRootNode;
import cc.concurrent.mango.util.Iterables;
import cc.concurrent.mango.util.TypeToken;
import cc.concurrent.mango.util.logging.InternalLogger;
import cc.concurrent.mango.util.logging.InternalLoggerFactory;

import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.*;

/**
 * @author ash
 */
public class BatchUpdateOperator extends CacheableOperator {

    private final static InternalLogger logger = InternalLoggerFactory.getInstance(BatchUpdateOperator.class);

    private ASTRootNode rootNode;

    private BatchUpdateOperator(ASTRootNode rootNode, Method method, SQLType sqlType) {
        super(method, sqlType);
        init(rootNode, method);
    }

    public void init(ASTRootNode rootNode, Method method) {
        this.rootNode = rootNode;

        if (method.getGenericParameterTypes().length != 1) {
            throw new IncorrectParameterCountException("batch update expected one and only one parameter but " +
                    method.getGenericParameterTypes().length); // 批量更新只能有一个参数
        }

        Type type = method.getGenericParameterTypes()[0];
        TypeToken typeToken = new TypeToken(type);
        Class<?> mappedClass = typeToken.getMappedClass();
        if (mappedClass == null || !typeToken.isIterable()) {
            throw new IncorrectParameterTypeException("parameter of batch update " +
                    "expected array or implementations of java.util.List or implementations of java.util.Set " +
                    "but " + type); // 批量更新的参数必须可迭代
        }

        TypeContext context = buildTypeContext(new Type[]{mappedClass});
        rootNode.checkType(context); // 检测sql中的参数是否和方法上的参数匹配
        checkCacheBy(rootNode); // 如果使用cache，检测cache参数

        List<ASTIterableParameter> aips = rootNode.getASTIterableParameters();
        if (aips.size() > 0) {
            throw new IncorrectSqlException("if use batch update, sql's in clause number expected 0 but " +
                    aips.size()); // sql中不能有in语句
        }
    }

    public static BatchUpdateOperator create(ASTRootNode rootNode, Method method, SQLType sqlType) {
        return new BatchUpdateOperator(rootNode, method, sqlType);
    }

    @Override
    public Object execute(Object[] methodArgs) {
        Object methodArg = methodArgs[0];
        if (methodArg == null) {
            throw new NullPointerException("batchUpdate's parameter can't be null");
        }
        Iterables iterables = new Iterables(methodArg);
        if (iterables.isEmpty()) {
            throw new IllegalArgumentException("batchUpdate's parameter can't be empty");
        }

        Set<String> keys = new HashSet<String>();
        List<Object[]> batchArgs = new ArrayList<Object[]>();
        String sql = null;
        for (Object obj : iterables) {
            RuntimeContext context = buildRuntimeContext(new Object[]{obj});
            if (isUseCache()) {
                keys.add(getSingleKey(context));
            }
            ParsedSql parsedSql= rootNode.buildSqlAndArgs(context);
            if (sql == null) {
                sql = parsedSql.getSql();
            }
            batchArgs.add(parsedSql.getArgs());
        }
        if (logger.isDebugEnabled()) {
            List<String> str = new ArrayList<String>();
            for (Object[] args : batchArgs) {
                str.add(Arrays.toString(args));
            }
            List<List<Object>> debugBatchArgs = new ArrayList<List<Object>>(batchArgs.size());
            for (Object[] batchArg : batchArgs) {
                debugBatchArgs.add(Arrays.asList(batchArg));
            }
            logger.debug("{} #args={}", sql, debugBatchArgs);
        }
        int[] ints = jdbcTemplate.batchUpdate(getDataSource(), sql, batchArgs);
        if (isUseCache()) {
            deleteFromCache(keys);
        }
        return ints;
    }

}