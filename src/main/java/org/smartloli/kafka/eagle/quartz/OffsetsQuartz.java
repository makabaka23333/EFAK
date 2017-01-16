/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.smartloli.kafka.eagle.quartz;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.google.gson.Gson;

import org.smartloli.kafka.eagle.domain.AlarmDomain;
import org.smartloli.kafka.eagle.domain.OffsetZkDomain;
import org.smartloli.kafka.eagle.domain.OffsetsLiteDomain;
import org.smartloli.kafka.eagle.domain.TupleDomain;
import org.smartloli.kafka.eagle.ipc.RpcClient;
import org.smartloli.kafka.eagle.service.OffsetService;
import org.smartloli.kafka.eagle.util.ZKDataUtils;
import org.smartloli.kafka.eagle.util.KafkaClusterUtils;
import org.smartloli.kafka.eagle.util.LRUCacheUtils;
import org.smartloli.kafka.eagle.util.SendMessageUtils;
import org.smartloli.kafka.eagle.util.SystemConfigUtils;

/**
 * Per 5 mins to stats offsets to offsets table.
 *
 * @author smartloli.
 *
 *         Created by Aug 18, 2016
 */
public class OffsetsQuartz {

	private static Logger LOG = LoggerFactory.getLogger(OffsetsQuartz.class);

	/** Cache to the specified map collection to prevent frequent refresh. */
	private static LRUCacheUtils<String, TupleDomain> lruCache = new LRUCacheUtils<String, TupleDomain>(100000);

	@Deprecated
	public void cleanHistoryData() {
		// Nothing to do
	}

	/** Get the corresponding string per minute. */
	private String getStatsPerDate() {
		SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm");
		return df.format(new Date());
	}

	/** Perform offset statistical tasks on time. */
	public void jobQuartz() {
		try {
			List<String> hosts = getBrokers();
			List<OffsetsLiteDomain> list = new ArrayList<OffsetsLiteDomain>();
			String formatter = SystemConfigUtils.getProperty("kafka.eagle.offset.storage");
			Map<String, List<String>> consumers = null;
			if ("kafka".equals(formatter)) {
				Map<String, List<String>> type = new HashMap<String, List<String>>();
				Gson gson = new Gson();
				consumers = gson.fromJson(RpcClient.getConsumer(), type.getClass());
			} else {
				consumers = KafkaClusterUtils.getConsumers();
			}
			String statsPerDate = getStatsPerDate();
			for (Entry<String, List<String>> entry : consumers.entrySet()) {
				String group = entry.getKey();
				for (String topic : entry.getValue()) {
					OffsetsLiteDomain offsetSQLite = new OffsetsLiteDomain();
					for (String partitionStr : KafkaClusterUtils.findTopicPartition(topic)) {
						int partition = Integer.parseInt(partitionStr);
						long logSize = KafkaClusterUtils.getLogSize(hosts, topic, partition);
						OffsetZkDomain offsetZk = null;
						if ("kafka".equals(formatter)) {
							offsetZk = OffsetService.getKafkaOffset(topic, group, partition);
						} else {
							offsetZk = KafkaClusterUtils.getOffset(topic, group, partition);
						}
						offsetSQLite.setGroup(group);
						offsetSQLite.setCreated(statsPerDate);
						offsetSQLite.setTopic(topic);
						if (logSize == 0) {
							offsetSQLite.setLag(0L + offsetSQLite.getLag());
						} else {
							long lag = offsetSQLite.getLag() + (offsetZk.getOffset() == -1 ? 0 : logSize - offsetZk.getOffset());
							offsetSQLite.setLag(lag);
						}
						offsetSQLite.setLogSize(logSize + offsetSQLite.getLogSize());
						offsetSQLite.setOffsets(offsetZk.getOffset() + offsetSQLite.getOffsets());
					}
					list.add(offsetSQLite);
				}
			}
			ZKDataUtils.insert(list);
			boolean alarmEnable = SystemConfigUtils.getBooleanProperty("kafka.eagel.mail.enable");
			if (alarmEnable) {
				List<AlarmDomain> listAlarm = alarmConfigure();
				for (AlarmDomain alarm : listAlarm) {
					for (OffsetsLiteDomain offset : list) {
						if (offset.getGroup().equals(alarm.getGroup()) && offset.getTopic().equals(alarm.getTopics()) && offset.getLag() > alarm.getLag()) {
							try {
								SendMessageUtils.send(alarm.getOwners(), "Alarm Lag",
										"Lag exceeds a specified threshold,Topic is [" + alarm.getTopics() + "],current lag is [" + offset.getLag() + "],expired lag is [" + alarm.getLag() + "].");
							} catch (Exception ex) {
								LOG.error("Topic[" + alarm.getTopics() + "] Send alarm mail has error,msg is " + ex.getMessage());
							}
						}
					}
				}
			}
		} catch (Exception ex) {
			LOG.error("[Quartz.offsets] has error,msg is " + ex.getMessage());
		}
	}

	/** Get kafka brokers. */
	private static List<String> getBrokers() {
		// Add LRUCache per 3 min
		String key = "group_topic_offset_graph_consumer_brokers";
		String brokers = "";
		if (lruCache.containsKey(key)) {
			TupleDomain tuple = lruCache.get(key);
			brokers = tuple.getRet();
			long end = System.currentTimeMillis();
			if ((end - tuple.getTimespan()) / (1000 * 60.0) > 30) {// 30 mins
				lruCache.remove(key);
			}
		} else {
			brokers = KafkaClusterUtils.getAllBrokersInfo();
			TupleDomain tuple = new TupleDomain();
			tuple.setRet(brokers);
			tuple.setTimespan(System.currentTimeMillis());
			lruCache.put(key, tuple);
		}
		JSONArray arr = JSON.parseArray(brokers);
		List<String> list = new ArrayList<String>();
		for (Object object : arr) {
			JSONObject obj = (JSONObject) object;
			String host = obj.getString("host");
			int port = obj.getInteger("port");
			list.add(host + ":" + port);
		}
		return list;
	}

	/** Get alarmer configure. */
	private static List<AlarmDomain> alarmConfigure() {
		String ret = ZKDataUtils.getAlarm();
		List<AlarmDomain> list = new ArrayList<>();
		JSONArray array = JSON.parseArray(ret);
		for (Object object : array) {
			AlarmDomain alarm = new AlarmDomain();
			JSONObject obj = (JSONObject) object;
			alarm.setGroup(obj.getString("group"));
			alarm.setTopics(obj.getString("topic"));
			alarm.setLag(obj.getLong("lag"));
			alarm.setOwners(obj.getString("owner"));
			list.add(alarm);
		}
		return list;
	}
}