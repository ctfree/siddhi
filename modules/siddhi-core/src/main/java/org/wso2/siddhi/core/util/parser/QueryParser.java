/*
 * Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.siddhi.core.util.parser;

import org.wso2.siddhi.core.config.ExecutionPlanContext;
import org.wso2.siddhi.core.event.state.populater.StateEventPopulatorFactory;
import org.wso2.siddhi.core.exception.ExecutionPlanCreationException;
import org.wso2.siddhi.core.executor.VariableExpressionExecutor;
import org.wso2.siddhi.core.query.QueryRuntime;
import org.wso2.siddhi.core.query.input.stream.StreamRuntime;
import org.wso2.siddhi.core.query.input.stream.single.SingleStreamRuntime;
import org.wso2.siddhi.core.query.output.callback.OutputCallback;
import org.wso2.siddhi.core.query.output.ratelimit.OutputRateLimiter;
import org.wso2.siddhi.core.query.output.ratelimit.snapshot.WrappedSnapshotOutputRateLimiter;
import org.wso2.siddhi.core.query.selector.QuerySelector;
import org.wso2.siddhi.core.table.EventTable;
import org.wso2.siddhi.core.util.SiddhiConstants;
import org.wso2.siddhi.core.util.parser.helper.QueryParserHelper;
import org.wso2.siddhi.core.util.statistics.LatencyTracker;
import org.wso2.siddhi.query.api.annotation.Element;
import org.wso2.siddhi.query.api.definition.AbstractDefinition;
import org.wso2.siddhi.query.api.exception.DuplicateDefinitionException;
import org.wso2.siddhi.query.api.execution.query.Query;
import org.wso2.siddhi.query.api.execution.query.input.handler.StreamHandler;
import org.wso2.siddhi.query.api.execution.query.input.handler.Window;
import org.wso2.siddhi.query.api.execution.query.input.stream.JoinInputStream;
import org.wso2.siddhi.query.api.execution.query.input.stream.SingleInputStream;
import org.wso2.siddhi.query.api.execution.query.output.stream.OutputStream;
import org.wso2.siddhi.query.api.util.AnnotationHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

public class QueryParser {

    /**
     * Parse a query and return corresponding QueryRuntime.
     *
     * @param query                query to be parsed.
     * @param executionPlanContext associated Execution Plan context.
     * @param streamDefinitionMap  map containing user given stream definitions.
     * @param tableDefinitionMap   map containing table definitions.
     * @param eventTableMap        map containing event tables.
     * @return queryRuntime.
     */
    public static QueryRuntime parse(Query query, ExecutionPlanContext executionPlanContext,
                                     Map<String, AbstractDefinition> streamDefinitionMap,
                                     Map<String, AbstractDefinition> tableDefinitionMap,
                                     Map<String, EventTable> eventTableMap) {
        List<VariableExpressionExecutor> executors = new ArrayList<VariableExpressionExecutor>();
        QueryRuntime queryRuntime;
        Element nameElement = null;
        LatencyTracker latencyTracker = null;
        ReentrantLock queryLock = null;
        try {
            nameElement = AnnotationHelper.getAnnotationElement("info", "name", query.getAnnotations());
            if (executionPlanContext.isStatsEnabled() && executionPlanContext.getStatisticsManager() != null) {
                if (nameElement != null) {
                    String metricName =
                            executionPlanContext.getSiddhiContext().getStatisticsConfiguration().getMatricPrefix() +
                                    SiddhiConstants.METRIC_DELIMITER + SiddhiConstants.METRIC_INFIX_EXECUTION_PLANS +
                                    SiddhiConstants.METRIC_DELIMITER + executionPlanContext.getName() +
                                    SiddhiConstants.METRIC_DELIMITER + SiddhiConstants.METRIC_INFIX_SIDDHI +
                                    SiddhiConstants.METRIC_DELIMITER + SiddhiConstants.METRIC_INFIX_QUERIES +
                                    SiddhiConstants.METRIC_DELIMITER + nameElement.getValue();
                    latencyTracker = executionPlanContext.getSiddhiContext()
                            .getStatisticsConfiguration()
                            .getFactory()
                            .createLatencyTracker(metricName, executionPlanContext.getStatisticsManager());
                }
            }
            OutputStream.OutputEventType outputEventType = query.getOutputStream().getOutputEventType();
            boolean outputExpectsExpiredEvents = false;
            if (outputEventType != OutputStream.OutputEventType.CURRENT_EVENTS) {
                outputExpectsExpiredEvents = true;
            }
            StreamRuntime streamRuntime = InputStreamParser.parse(query.getInputStream(),
                    executionPlanContext, streamDefinitionMap, tableDefinitionMap, eventTableMap, executors, latencyTracker, outputExpectsExpiredEvents);
            QuerySelector selector = SelectorParser.parse(query.getSelector(), query.getOutputStream(),
                    executionPlanContext, streamRuntime.getMetaComplexEvent(), eventTableMap, executors);
            boolean isWindow = query.getInputStream() instanceof JoinInputStream;
            if (!isWindow && query.getInputStream() instanceof SingleInputStream) {
                for (StreamHandler streamHandler : ((SingleInputStream) query.getInputStream()).getStreamHandlers()) {
                    if (streamHandler instanceof Window) {
                        isWindow = true;
                        break;
                    }
                }
            }

            Element synchronizedElement = AnnotationHelper.getAnnotationElement("synchronized", null, query.getAnnotations());
            if (synchronizedElement != null) {
                if (!("false".equalsIgnoreCase(synchronizedElement.getValue()))) {
                    queryLock = new ReentrantLock();
                }
            } else {
                if (isWindow || !(streamRuntime instanceof SingleStreamRuntime)) {
                    queryLock = new ReentrantLock();
                }
            }

            OutputRateLimiter outputRateLimiter = OutputParser.constructOutputRateLimiter(query.getOutputStream().getId(),
                    query.getOutputRate(), query.getSelector().getGroupByList().size() != 0, isWindow,
                    executionPlanContext.getScheduledExecutorService(), executionPlanContext);
            if (outputRateLimiter instanceof WrappedSnapshotOutputRateLimiter) {
                selector.setBatchingEnabled(false);
            }
            executionPlanContext.addEternalReferencedHolder(outputRateLimiter);

            OutputCallback outputCallback = OutputParser.constructOutputCallback(query.getOutputStream(),
                    streamRuntime.getMetaComplexEvent().getOutputStreamDefinition(), eventTableMap, executionPlanContext, !(streamRuntime instanceof SingleStreamRuntime));

            QueryParserHelper.reduceMetaComplexEvent(streamRuntime.getMetaComplexEvent());
            QueryParserHelper.updateVariablePosition(streamRuntime.getMetaComplexEvent(), executors);
            QueryParserHelper.initStreamRuntime(streamRuntime, streamRuntime.getMetaComplexEvent(), queryLock);
            selector.setEventPopulator(StateEventPopulatorFactory.constructEventPopulator(streamRuntime.getMetaComplexEvent()));
            queryRuntime = new QueryRuntime(query, executionPlanContext, streamRuntime, selector, outputRateLimiter,
                    outputCallback, streamRuntime.getMetaComplexEvent(), queryLock != null);

            if (outputRateLimiter instanceof WrappedSnapshotOutputRateLimiter) {
                selector.setBatchingEnabled(false);
                ((WrappedSnapshotOutputRateLimiter) outputRateLimiter)
                        .init(streamRuntime.getMetaComplexEvent().getOutputStreamDefinition().getAttributeList().size(),
                                selector.getAttributeProcessorList(), streamRuntime.getMetaComplexEvent());
            }
            outputRateLimiter.init(executionPlanContext, queryLock);

        } catch (DuplicateDefinitionException e) {
            if (nameElement != null) {
                throw new DuplicateDefinitionException(e.getMessage() + ", when creating query " + nameElement.getValue(), e);
            } else {
                throw new DuplicateDefinitionException(e.getMessage(), e);
            }
        } catch (RuntimeException e) {
            if (nameElement != null) {
                throw new ExecutionPlanCreationException(e.getMessage() + ", when creating query " + nameElement.getValue(), e);
            } else {
                throw new ExecutionPlanCreationException(e.getMessage(), e);
            }
        }
        return queryRuntime;
    }
}
