/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.distsql.parser.core.advanced;

import org.antlr.v4.runtime.misc.ParseCancellationException;
import org.antlr.v4.runtime.tree.ErrorNode;
import org.apache.shardingsphere.sql.parser.api.visitor.ASTNode;
import org.apache.shardingsphere.sql.parser.core.ParseASTNode;
import org.apache.shardingsphere.sql.parser.core.SQLParserFactory;
import org.apache.shardingsphere.sql.parser.exception.SQLParsingException;
import org.apache.shardingsphere.sql.parser.sql.common.statement.SQLStatement;

/**
 * SQL statement parser engine for advanced dist SQL.
 */
public final class AdvancedDistSQLStatementParserEngine {
    
    /**
     * Parse SQL.
     *
     * @param sql SQL to be parsed
     * @return SQL statement
     */
    public SQLStatement parse(final String sql) {
        ASTNode astNode = parseToASTNode(sql);
        return getSQLStatement(sql, (ParseASTNode) astNode);
    }
    
    private ASTNode parseToASTNode(final String sql) {
        try {
            ASTNode result = SQLParserFactory.newInstance(sql, AdvancedDistSQLLexer.class, AdvancedDistSQLParser.class).parse();
            return result;
        } catch (final ParseCancellationException | SQLParsingException ignored) {
            throw new SQLParsingException("You have an error in your SQL syntax.");
        }
    }
    
    private SQLStatement getSQLStatement(final String sql, final ParseASTNode parseASTNode) {
        if (parseASTNode.getRootNode() instanceof ErrorNode) {
            throw new SQLParsingException("Unsupported SQL of `%s`", sql);
        }
        return (SQLStatement) (new AdvancedDistSQLStatementVisitor()).visit(parseASTNode.getRootNode());
    }
}
