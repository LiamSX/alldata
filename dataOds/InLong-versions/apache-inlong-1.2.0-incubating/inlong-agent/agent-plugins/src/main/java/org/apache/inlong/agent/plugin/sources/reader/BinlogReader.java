/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.inlong.agent.plugin.sources.reader;

import com.google.common.base.Preconditions;
import com.google.gson.Gson;
import io.debezium.connector.mysql.MySqlConnector;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import io.debezium.relational.history.FileDatabaseHistory;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.inlong.agent.conf.AgentConfiguration;
import org.apache.inlong.agent.conf.JobProfile;
import org.apache.inlong.agent.constant.AgentConstants;
import org.apache.inlong.agent.constant.CommonConstants;
import org.apache.inlong.agent.constant.SnapshotModeConstants;
import org.apache.inlong.agent.message.DefaultMessage;
import org.apache.inlong.agent.plugin.Message;
import org.apache.inlong.agent.plugin.Reader;
import org.apache.inlong.agent.plugin.sources.snapshot.BinlogSnapshotBase;
import org.apache.inlong.agent.plugin.utils.InLongDatabaseHistory;
import org.apache.inlong.agent.plugin.utils.InLongFileOffsetBackingStore;
import org.apache.inlong.agent.pojo.DebeziumFormat;
import org.apache.inlong.agent.pojo.DebeziumOffset;
import org.apache.inlong.agent.utils.AgentUtils;
import org.apache.inlong.agent.utils.DebeziumOffsetSerializer;
import org.apache.inlong.common.reporpter.ConfigLogTypeEnum;
import org.apache.inlong.common.reporpter.StreamConfigLogMetric;
import org.apache.kafka.connect.storage.FileOffsetBackingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

import static org.apache.inlong.agent.constant.CommonConstants.DEFAULT_MAP_CAPACITY;
import static org.apache.inlong.agent.constant.CommonConstants.PROXY_KEY_DATA;

/**
 * read binlog data
 */
public class BinlogReader implements Reader {

    public static final String COMPONENT_NAME = "BinlogReader";
    public static final String JOB_DATABASE_USER = "job.binlogJob.user";
    public static final String JOB_DATABASE_PASSWORD = "job.binlogJob.password";
    public static final String JOB_DATABASE_HOSTNAME = "job.binlogJob.hostname";
    public static final String JOB_TABLE_WHITELIST = "job.binlogJob.tableWhiteList";
    public static final String JOB_DATABASE_WHITELIST = "job.binlogJob.databaseWhiteList";
    public static final String JOB_DATABASE_OFFSETS = "job.binlogJob.offsets";
    public static final String JOB_DATABASE_OFFSET_SPECIFIC_OFFSET_FILE = "job.binlogJob.offset.specificOffsetFile";
    public static final String JOB_DATABASE_OFFSET_SPECIFIC_OFFSET_POS = "job.binlogJob.offset.specificOffsetPos";
    public static final String JOB_DATABASE_SERVER_TIME_ZONE = "job.binlogJob.serverTimezone";
    public static final String JOB_DATABASE_STORE_OFFSET_INTERVAL_MS = "job.binlogJob.offset.intervalMs";
    public static final String JOB_DATABASE_STORE_HISTORY_FILENAME = "job.binlogJob.history.filename";
    public static final String JOB_DATABASE_INCLUDE_SCHEMA_CHANGES = "job.binlogJob.schema";
    public static final String JOB_DATABASE_SNAPSHOT_MODE = "job.binlogJob.snapshot.mode";
    public static final String JOB_DATABASE_HISTORY_MONITOR_DDL = "job.binlogJob.ddl";
    public static final String JOB_DATABASE_PORT = "job.binlogJob.port";
    public static final String JOB_DATABASE_QUEUE_SIZE = "job.binlogJob.queueSize";
    private static final Logger LOGGER = LoggerFactory.getLogger(BinlogReader.class);
    private static final Gson gson = new Gson();
    private final AgentConfiguration agentConf = AgentConfiguration.getAgentConf();
    /**
     * pair.left: table name
     * pair.right: actual data
     */
    private LinkedBlockingQueue<Pair<String, String>> binlogMessagesQueue;
    private boolean finished = false;
    private String userName;
    private String password;
    private String hostName;
    private String port;
    private String tableWhiteList;
    private String databaseWhiteList;
    private String serverTimeZone;
    private String offsetStoreFileName;
    private String offsetFlushIntervalMs;
    private String databaseStoreHistoryName;
    private String includeSchemaChanges;
    private String snapshotMode;
    private String historyMonitorDdl;
    private String instanceId;
    private ExecutorService executor;
    private String offset;
    private String specificOffsetFile;
    private String specificOffsetPos;
    private BinlogSnapshotBase binlogSnapshot;
    private JobProfile jobProfile;
    private boolean destroyed = false;
    private boolean enableReportConfigLog;
    private StreamConfigLogMetric streamConfigLogMetric;
    private String inlongGroupId;
    private String inlongStreamId;

    public BinlogReader() {
    }

    @Override
    public Message read() {
        if (!binlogMessagesQueue.isEmpty()) {
            return getBinlogMessage();
        } else {
            return null;
        }
    }

    private DefaultMessage getBinlogMessage() {
        Pair<String, String> message = binlogMessagesQueue.poll();
        Map<String, String> header = new HashMap<>(DEFAULT_MAP_CAPACITY);
        header.put(PROXY_KEY_DATA, message.getKey());
        return new DefaultMessage(message.getValue().getBytes(StandardCharsets.UTF_8),
                header);
    }

    @Override
    public void init(JobProfile jobConf) {
        jobProfile = jobConf;
        LOGGER.info("init binlog reader with jobConf {}", jobConf.toJsonStr());
        userName = jobConf.get(JOB_DATABASE_USER);
        password = jobConf.get(JOB_DATABASE_PASSWORD);
        hostName = jobConf.get(JOB_DATABASE_HOSTNAME);
        port = jobConf.get(JOB_DATABASE_PORT);
        tableWhiteList = jobConf.get(JOB_TABLE_WHITELIST, "[\\s\\S]*.*");
        databaseWhiteList = jobConf.get(JOB_DATABASE_WHITELIST, "");
        serverTimeZone = jobConf.get(JOB_DATABASE_SERVER_TIME_ZONE, "");
        offsetFlushIntervalMs = jobConf.get(JOB_DATABASE_STORE_OFFSET_INTERVAL_MS, "100000");
        databaseStoreHistoryName = jobConf.get(JOB_DATABASE_STORE_HISTORY_FILENAME,
                tryToInitAndGetHistoryPath()) + "/history.dat" + jobConf.getInstanceId();
        offsetStoreFileName = jobConf.get(JOB_DATABASE_STORE_HISTORY_FILENAME,
                tryToInitAndGetHistoryPath()) + "/offset.dat" + jobConf.getInstanceId();
        snapshotMode = jobConf.get(JOB_DATABASE_SNAPSHOT_MODE, "");
        includeSchemaChanges = jobConf.get(JOB_DATABASE_INCLUDE_SCHEMA_CHANGES, "false");
        historyMonitorDdl = jobConf.get(JOB_DATABASE_HISTORY_MONITOR_DDL, "false");
        binlogMessagesQueue = new LinkedBlockingQueue<>(jobConf.getInt(JOB_DATABASE_QUEUE_SIZE, 1000));
        instanceId = jobConf.getInstanceId();
        finished = false;

        offset = jobConf.get(JOB_DATABASE_OFFSETS, "");
        specificOffsetFile = jobConf.get(JOB_DATABASE_OFFSET_SPECIFIC_OFFSET_FILE, "");
        specificOffsetPos = jobConf.get(JOB_DATABASE_OFFSET_SPECIFIC_OFFSET_POS, "-1");
        binlogSnapshot = new BinlogSnapshotBase(offsetStoreFileName);
        binlogSnapshot.save(offset);

        enableReportConfigLog =
                Boolean.parseBoolean(jobConf.get(StreamConfigLogMetric.CONFIG_LOG_REPORT_ENABLE,
                        "true"));

        inlongGroupId = jobConf.get(CommonConstants.PROXY_INLONG_GROUP_ID,
                CommonConstants.DEFAULT_PROXY_INLONG_GROUP_ID);
        inlongStreamId = jobConf.get(CommonConstants.PROXY_INLONG_STREAM_ID,
                CommonConstants.DEFAULT_PROXY_INLONG_STREAM_ID);

        if (enableReportConfigLog) {
            String reportConfigServerUrl = jobConf
                    .get(StreamConfigLogMetric.CONFIG_LOG_REPORT_SERVER_URL, "");
            String reportConfigLogInterval = jobConf
                    .get(StreamConfigLogMetric.CONFIG_LOG_REPORT_INTERVAL, "60000");
            String clientVersion = jobConf
                    .get(StreamConfigLogMetric.CONFIG_LOG_REPORT_CLIENT_VERSION, "");
            streamConfigLogMetric = new StreamConfigLogMetric(COMPONENT_NAME,
                    reportConfigServerUrl, Long.parseLong(reportConfigLogInterval),
                    AgentUtils.getLocalIp(), clientVersion);
        }

        Properties props = getEngineProps();

        DebeziumEngine<ChangeEvent<String, String>> engine = DebeziumEngine.create(
                io.debezium.engine.format.Json.class)
                .using(props)
                .notifying((records, committer) -> {
                    try {
                        for (ChangeEvent<String, String> record : records) {
                            DebeziumFormat debeziumFormat = gson
                                    .fromJson(record.value(), DebeziumFormat.class);
                            binlogMessagesQueue.put(Pair.of(debeziumFormat.getSource().getTable(),
                                    record.value()));
                            committer.markProcessed(record);
                        }
                        committer.markBatchFinished();
                    } catch (Exception e) {
                        LOGGER.error("parse binlog message error", e);

                    }

                })
                .using((success, message, error) -> {
                    if (!success) {
                        LOGGER.error("binlog job with jobConf {} has " + "error {}",
                                jobConf.getInstanceId(), message, error);
                        streamConfigLogMetric
                                .updateConfigLog(inlongGroupId, inlongStreamId, "DBConfig",
                                        ConfigLogTypeEnum.ERROR, error == null ? "" : error.toString());
                    }
                }).build();

        executor = Executors.newSingleThreadExecutor();
        executor.execute(engine);

        LOGGER.info("get initial snapshot of job {}, snapshot {}",
                jobConf.getInstanceId(), getSnapshot());
    }

    private Properties getEngineProps() {
        Properties props = new Properties();

        props.setProperty("name", "engine" + instanceId);
        props.setProperty("connector.class", MySqlConnector.class.getCanonicalName());

        props.setProperty("database.server.name", instanceId);
        props.setProperty("database.hostname", hostName);
        props.setProperty("database.port", port);
        props.setProperty("database.user", userName);
        props.setProperty("database.password", password);
        props.setProperty("database.serverTimezone", serverTimeZone);
        props.setProperty("table.whitelist", tableWhiteList);
        props.setProperty("database.whitelist", databaseWhiteList);

        props.setProperty("offset.flush.interval.ms", offsetFlushIntervalMs);
        props.setProperty("database.snapshot.mode", snapshotMode);
        props.setProperty("database.history.store.only.monitored.tables.ddl", historyMonitorDdl);
        props.setProperty("database.allowPublicKeyRetrieval", "true");
        props.setProperty("key.converter.schemas.enable", "false");
        props.setProperty("value.converter.schemas.enable", "false");
        props.setProperty("include.schema.changes", includeSchemaChanges);
        props.setProperty("snapshot.mode", snapshotMode);
        props.setProperty("offset.storage.file.filename", offsetStoreFileName);
        props.setProperty("database.history.file.filename", databaseStoreHistoryName);
        if (SnapshotModeConstants.SPECIFIC_OFFSETS.equals(snapshotMode)) {
            props.setProperty("offset.storage", InLongFileOffsetBackingStore.class.getCanonicalName());
            props.setProperty(InLongFileOffsetBackingStore.OFFSET_STATE_VALUE, serializeOffset());
            props.setProperty("database.history", InLongDatabaseHistory.class.getCanonicalName());
        } else {
            props.setProperty("offset.storage", FileOffsetBackingStore.class.getCanonicalName());
            props.setProperty("database.history", FileDatabaseHistory.class.getCanonicalName());
        }
        props.setProperty("tombstones.on.delete", "false");
        props.setProperty("converters", "datetime");
        props.setProperty("datetime.type", "org.apache.inlong.agent.plugin.utils.BinlogTimeConverter");
        props.setProperty("datetime.format.date", "yyyy-MM-dd");
        props.setProperty("datetime.format.time", "HH:mm:ss");
        props.setProperty("datetime.format.datetime", "yyyy-MM-dd HH:mm:ss");
        props.setProperty("datetime.format.timestamp", "yyyy-MM-dd HH:mm:ss");
        props.setProperty("datetime.format.timestamp.zone", serverTimeZone);

        LOGGER.info("binlog job {} start with props {}", jobProfile.getInstanceId(), props);
        return props;
    }

    private String serializeOffset() {
        Map<String, Object> sourceOffset = new HashMap<>();
        Preconditions.checkNotNull(JOB_DATABASE_OFFSET_SPECIFIC_OFFSET_FILE,
                JOB_DATABASE_OFFSET_SPECIFIC_OFFSET_FILE + "shouldn't be null");
        sourceOffset.put("file", specificOffsetFile);
        Preconditions.checkNotNull(JOB_DATABASE_OFFSET_SPECIFIC_OFFSET_POS,
                JOB_DATABASE_OFFSET_SPECIFIC_OFFSET_POS + " shouldn't be null");
        sourceOffset.put("pos", specificOffsetPos);
        DebeziumOffset specificOffset = new DebeziumOffset();
        specificOffset.setSourceOffset(sourceOffset);
        Map<String, String> sourcePartition = new HashMap<>();
        sourcePartition.put("server", instanceId);
        specificOffset.setSourcePartition(sourcePartition);
        byte[] serializedOffset = new byte[0];
        try {
            serializedOffset = DebeziumOffsetSerializer.INSTANCE.serialize(specificOffset);
        } catch (IOException e) {
            LOGGER.error("serialize offset message error", e);
        }
        return new String(serializedOffset, StandardCharsets.UTF_8);
    }

    @Override
    public void destroy() {
        synchronized (this) {
            if (!destroyed) {
                executor.shutdownNow();
                binlogSnapshot.close();
                destroyed = true;
            }
        }
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    @Override
    public String getReadSource() {
        return instanceId;
    }

    @Override
    public void setReadTimeout(long mill) {
        return;
    }

    @Override
    public void setWaitMillisecs(long millis) {
        return;
    }

    @Override
    public String getSnapshot() {
        if (binlogSnapshot != null) {
            return binlogSnapshot.getSnapshot();
        } else {
            return "";
        }
    }

    @Override
    public void finishRead() {
        finished = true;
    }

    @Override
    public boolean isSourceExist() {
        return true;
    }

    private String tryToInitAndGetHistoryPath() {
        String historyPath = agentConf.get(
                AgentConstants.AGENT_HISTORY_PATH, AgentConstants.DEFAULT_AGENT_HISTORY_PATH);
        String parentPath = agentConf.get(
                AgentConstants.AGENT_HOME, AgentConstants.DEFAULT_AGENT_HOME);
        return AgentUtils.makeDirsIfNotExist(historyPath, parentPath).getAbsolutePath();
    }

}
