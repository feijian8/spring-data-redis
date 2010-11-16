/*
 * Copyright (c) 2010 by J. Brisbin <jon@jbrisbin.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.datastore.riak.core;

import org.codehaus.groovy.runtime.GStringImpl;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.ser.CustomSerializerFactory;
import org.codehaus.jackson.map.ser.ToStringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.convert.support.ConversionServiceFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.datastore.riak.DataStoreOperationException;
import org.springframework.datastore.riak.convert.KeyValueStoreMetaData;
import org.springframework.datastore.riak.mapreduce.MapReduceJob;
import org.springframework.datastore.riak.mapreduce.MapReduceOperations;
import org.springframework.datastore.riak.mapreduce.RiakMapReduceJob;
import org.springframework.http.*;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJacksonHttpMessageConverter;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.web.client.*;
import org.springframework.web.client.support.RestGatewaySupport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author J. Brisbin <jon@jbrisbin.com>
 */
@SuppressWarnings({"unchecked"})
public class RiakTemplate extends RestGatewaySupport implements KeyValueStoreOperations, MapReduceOperations, InitializingBean {

  private static final String RIAK_CLIENT_ID = "org.springframework.datastore.riak.core.RiakTemplate/1.0";
  private static final Pattern prefix = Pattern.compile("http[s]?://(\\S+):([0-9]+)/(\\S+)/\\{bucket\\}(\\S+)");
  private static final boolean groovyPresent = ClassUtils.isPresent("org.codehaus.groovy.runtime.GStringImpl",
      RiakTemplate.class.getClassLoader());

  private static SimpleDateFormat httpDate = new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss z");

  protected final Logger log = LoggerFactory.getLogger(getClass());
  protected ConversionService conversionService = ConversionServiceFactory.createDefaultConversionService();
  protected ConcurrentSkipListMap<BucketKeyPair, RiakValue<?>> cache = new ConcurrentSkipListMap<BucketKeyPair, RiakValue<?>>();
  protected boolean useCache = true;
  protected ExecutorService queue = Executors.newCachedThreadPool();

  protected String defaultUri = "http://localhost:8098/riak/{bucket}/{key}";
  protected String mapReduceUri = "http://localhost:8098/mapred";
  protected List<BucketKeyResolver> bucketKeyResolvers;

  public RiakTemplate() {
    setRestTemplate(new RestTemplate());
  }

  public RiakTemplate(ClientHttpRequestFactory requestFactory) {
    super(requestFactory);
  }

  public RiakTemplate(String defaultUri) {
    setRestTemplate(new RestTemplate());
    setDefaultUri(defaultUri);
  }

  public RiakTemplate(String defaultUri, String mapReduceUri) {
    setRestTemplate(new RestTemplate());
    this.setDefaultUri(defaultUri);
    this.mapReduceUri = mapReduceUri;
  }

  public ConversionService getConversionService() {
    return conversionService;
  }

  public void setConversionService(ConversionService conversionService) {
    this.conversionService = conversionService;
  }

  public String getDefaultUri() {
    return defaultUri;
  }

  public void setDefaultUri(String defaultUri) {
    this.defaultUri = defaultUri;
  }

  public String getMapReduceUri() {
    return mapReduceUri;
  }

  public void setMapReduceUri(String mapReduceUri) {
    this.mapReduceUri = mapReduceUri;
  }

  public List<BucketKeyResolver> getBucketKeyResolvers() {
    return bucketKeyResolvers;
  }

  public void setBucketKeyResolvers(List<BucketKeyResolver> bucketKeyResolvers) {
    this.bucketKeyResolvers = bucketKeyResolvers;
  }

  public boolean isUseCache() {
    return useCache;
  }

  public void setUseCache(boolean useCache) {
    this.useCache = useCache;
  }

  /*----------------- Set Operations -----------------*/

  public <K, V> KeyValueStoreOperations set(K key, V value) {
    BucketKeyPair bucketKeyPair = resolveBucketKeyPair(key, value);
    RestTemplate restTemplate = getRestTemplate();
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Riak-ClientId", RIAK_CLIENT_ID);
    headers.setContentType(extractMediaType(value));
    HttpEntity<V> entity = new HttpEntity<V>(value, headers);
    restTemplate.put(defaultUri, entity, bucketKeyPair.getBucket(), bucketKeyPair.getKey());
    if (log.isDebugEnabled()) {
      log.debug(String.format("PUT object: bucket=%s, key=%s, value=%s",
          bucketKeyPair.getBucket(),
          bucketKeyPair.getKey(),
          value));
    }
    return this;
  }

  public <K> KeyValueStoreOperations setAsBytes(K key, byte[] value) {
    Assert.notNull(key, "Can't store an object with a NULL key.");
    BucketKeyPair bucketKeyPair = resolveBucketKeyPair(key, value);
    String bucketName = (null != bucketKeyPair.getBucket() ? bucketKeyPair.getBucket().toString() : "bytes");
    RestTemplate restTemplate = getRestTemplate();
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Riak-ClientId", RIAK_CLIENT_ID);
    headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
    HttpEntity<byte[]> entity = new HttpEntity<byte[]>(value, headers);
    restTemplate.put(defaultUri, entity, bucketName, bucketKeyPair.getKey());
    if (log.isDebugEnabled()) {
      log.debug(String.format("PUT byte[]: bucket=%s, key=%s", bucketKeyPair.getBucket(), bucketKeyPair.getKey()));
    }
    return this;
  }

  /*----------------- Get Operations -----------------*/

  public <K, T> RiakValue<T> getWithMetaData(K key, Class<T> requiredType) {
    BucketKeyPair bucketKeyPair = resolveBucketKeyPair(key, null);
    String bucketName = (null != bucketKeyPair.getBucket() ? bucketKeyPair.getBucket()
        .toString() : requiredType.getName());
    RestTemplate restTemplate = getRestTemplate();
    if (log.isDebugEnabled()) {
      log.debug(String.format("GET object: bucket=%s, key=%s, type=%s",
          bucketName,
          bucketKeyPair.getKey(),
          requiredType.getName()));
    }

    try {
      ResponseEntity<T> result = restTemplate.getForEntity(defaultUri,
          requiredType,
          bucketName,
          bucketKeyPair.getKey());
      if (result.hasBody()) {
        RiakMetaData meta = extractMetaData(result.getHeaders());
        RiakValue val = new RiakValue(result.getBody(), meta);
        if (useCache) {
          cache.put(bucketKeyPair, val);
        }
        return val;
      }
    } catch (HttpClientErrorException e) {
      if (e.getStatusCode() != HttpStatus.NOT_FOUND) {
        throw new DataStoreOperationException(e.getMessage(), e);
      }
    } catch (IOException e) {
      log.error(e.getMessage(), e);
    }
    return null;
  }

  public <K, V> V get(K key) {
    BucketKeyPair bucketKeyPair = resolveBucketKeyPair(key, null);
    Class targetClass;
    try {
      targetClass = Class.forName(bucketKeyPair.getBucket().toString());
    } catch (Throwable ignored) {
      targetClass = Map.class;
    }
    return (V) getWithMetaData(bucketKeyPair, targetClass).get();
  }

  public <K> byte[] getAsBytes(K key) {
    return getAsBytesWithMetaData(key).get();
  }

  public <K> RiakValue<byte[]> getAsBytesWithMetaData(K key) {
    BucketKeyPair bucketKeyPair = resolveBucketKeyPair(key, null);
    final RestTemplate restTemplate = getRestTemplate();
    if (log.isDebugEnabled()) {
      log.debug(String.format("GET object: bucket=%s, key=%s, type=byte[]",
          bucketKeyPair.getBucket(),
          bucketKeyPair.getKey()));
    }

    try {
      RiakValue<byte[]> bytes = (RiakValue<byte[]>) restTemplate.execute(defaultUri,
          HttpMethod.GET,
          new RequestCallback() {
            public void doWithRequest(ClientHttpRequest request) throws IOException {
              List<MediaType> mediaTypes = new ArrayList<MediaType>();
              mediaTypes.add(MediaType.APPLICATION_JSON);
              request.getHeaders().setAccept(mediaTypes);
            }
          },
          new ResponseExtractor<Object>() {
            public Object extractData(ClientHttpResponse response) throws IOException {
              InputStream in = response.getBody();
              ByteArrayOutputStream out = new ByteArrayOutputStream();
              byte[] buff = new byte[in.available()];
              for (int bytesRead = in.read(buff); bytesRead > 0; bytesRead = in.read(buff)) {
                out.write(buff, 0, bytesRead);
              }

              HttpHeaders headers = response.getHeaders();
              RiakMetaData meta = extractMetaData(headers);
              RiakValue<byte[]> val = new RiakValue<byte[]>(out.toByteArray(), meta);
              return val;
            }
          },
          bucketKeyPair.getBucket(),
          bucketKeyPair.getKey());
      if (useCache) {
        cache.put(bucketKeyPair, bytes);
      }
      return bytes;
    } catch (HttpClientErrorException e) {
      if (e.getStatusCode() != HttpStatus.NOT_FOUND) {
        throw new DataStoreOperationException(e.getMessage(), e);
      }
    }
    return null;
  }

  public <K, T> T getAsType(K key, Class<T> requiredType) {
    if (useCache) {
      Object obj = checkCache(key, requiredType);
      if (null != obj) {
        return (T) obj;
      }
    }
    return getWithMetaData(key, requiredType).get();
  }

  public <K, V> V getAndSet(K key, V value) {
    V old = (V) getAsType(key, value.getClass());
    set(key, value);
    return old;
  }

  public <K> byte[] getAndSetAsBytes(K key, byte[] value) {
    byte[] old = getAsBytes(key);
    setAsBytes(key, value);
    return old;
  }

  public <K, V, T> T getAndSetAsType(K key, V value, Class<T> requiredType) {
    T old = getAsType(key, requiredType);
    set(key, value);
    return old;
  }

  public <K, V> List<V> getValues(List<K> keys) {
    List<V> results = new ArrayList<V>();
    for (K key : keys) {
      BucketKeyPair bkp = resolveBucketKeyPair(key, null);
      results.add((V) get(bkp));
    }
    return results;
  }

  public <K, V> List<V> getValues(K... keys) {
    return getValues(keys);
  }

  public <K, T> List<T> getValuesAsType(List<K> keys, Class<T> requiredType) {
    List<T> results = new ArrayList<T>();
    for (K key : keys) {
      BucketKeyPair bkp = resolveBucketKeyPair(key, null);
      results.add(getAsType(bkp, requiredType));
    }
    return results;
  }

  public <T, K> List<T> getValuesAsType(Class<T> requiredType, K... keys) {
    List<K> keyList = new ArrayList<K>(keys.length);
    return getValuesAsType(keyList, requiredType);
  }

  /*----------------- Only-Set-Once Operations -----------------*/

  public <K, V> KeyValueStoreOperations setIfKeyNonExistent(K key, V value) {
    if (!containsKey(key)) {
      set(key, value);
    } else {
      if (log.isDebugEnabled()) {
        log.debug(String.format("key: %s already exists. Not adding %s", key, value));
      }
    }
    return this;
  }

  public <K> KeyValueStoreOperations setIfKeyNonExistentAsBytes(K key, byte[] value) {
    if (!containsKey(key)) {
      setAsBytes(key, value);
    } else {
      if (log.isDebugEnabled()) {
        log.debug(String.format("key: %s already exists. Not adding %s", key, value));
      }
    }
    return this;
  }

  /*----------------- Multiple Item Operations -----------------*/

  public <K, V> KeyValueStoreOperations setMultiple(Map<K, V> keysAndValues) {
    for (Map.Entry<K, V> entry : keysAndValues.entrySet()) {
      set(entry.getKey(), entry.getValue());
    }
    return this;
  }

  public <K> KeyValueStoreOperations setMultipleAsBytes(Map<K, byte[]> keysAndValues) {
    for (Map.Entry<K, byte[]> entry : keysAndValues.entrySet()) {
      setAsBytes(entry.getKey(), entry.getValue());
    }
    return this;
  }

  public <K, V> KeyValueStoreOperations setMultipleIfKeysNonExistent(Map<K, V> keysAndValues) {
    for (Map.Entry<K, V> entry : keysAndValues.entrySet()) {
      setIfKeyNonExistent(entry.getKey(), entry.getValue());
    }
    return this;
  }

  public <K> KeyValueStoreOperations setMultipleAsBytesIfKeysNonExistent(Map<K, byte[]> keysAndValues) {
    for (Map.Entry<K, byte[]> entry : keysAndValues.entrySet()) {
      setIfKeyNonExistentAsBytes(entry.getKey(), entry.getValue());
    }
    return this;
  }

  /*----------------- Key Operations -----------------*/

  public <K> boolean containsKey(K key) {
    BucketKeyPair bucketKeyPair = resolveBucketKeyPair(key, null);
    RestTemplate restTemplate = getRestTemplate();
    HttpHeaders headers = null;
    try {
      headers = restTemplate.headForHeaders(defaultUri, bucketKeyPair.getBucket(), bucketKeyPair.getKey());
    } catch (ResourceAccessException e) {
    }
    return (null != headers);
  }

  public <K> boolean deleteKeys(K... keys) {
    boolean stillExists = false;
    RestTemplate restTemplate = getRestTemplate();
    for (K key : keys) {
      BucketKeyPair bkp = resolveBucketKeyPair(key, null);
      try {
        restTemplate.delete(defaultUri, bkp.getBucket(), bkp.getKey());
      } catch (HttpClientErrorException e) {
        if (e.getStatusCode() != HttpStatus.NOT_FOUND) {
          throw new DataAccessResourceFailureException(e.getMessage(), e);
        }
      }
      if (!stillExists) {
        stillExists = containsKey(key);
      }
    }
    return !stillExists;
  }

  /*----------------- Map/Reduce Operations -----------------*/

  public RiakMapReduceJob createMapReduceJob() {
    return new RiakMapReduceJob(this);
  }

  public Object execute(MapReduceJob job) {
    return execute(job, List.class);
  }

  public <T> T execute(MapReduceJob job, Class<T> targetType) {
    RestTemplate restTemplate = getRestTemplate();
    ResponseEntity<T> resp = restTemplate.postForEntity(mapReduceUri, job.toJson(), targetType);
    if (resp.hasBody()) {
      return resp.getBody();
    }
    return null;
  }

  public <T> Future<List<T>> submit(MapReduceJob job) {
    return queue.submit(job);
  }

  /*----------------- Link Operations -----------------*/

  public <K1, K2> RiakTemplate link(K1 destination, K2 source, String tag) {
    BucketKeyPair bkpFrom = resolveBucketKeyPair(source, null);
    BucketKeyPair bkpTo = resolveBucketKeyPair(destination, null);
    RestTemplate restTemplate = getRestTemplate();

    RiakValue<byte[]> fromObj = getAsBytesWithMetaData(source);
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(fromObj.getMetaData().getContentType());
    Object linksObj = fromObj.getMetaData().getProperties().get("Link");
    List<String> links = new ArrayList<String>();
    if (linksObj instanceof List) {
      links.addAll((List) linksObj);
    } else if (linksObj instanceof String) {
      links.add(linksObj.toString());
    }
    links.add(String.format("<%s/%s/%s>; riaktag=\"%s\"", extractPrefix(), bkpTo.getBucket(), bkpTo.getKey(), tag));
    for (String link : links) {
      headers.set("Link", link);
    }
    HttpEntity entity = new HttpEntity(fromObj.get(), headers);
    restTemplate.put(defaultUri, entity, bkpFrom.getBucket(), bkpFrom.getKey());

    return this;
  }

  /*----------------- Bucket Operations -----------------*/

  public <B> Map<String, Object> getBucketSchema(B bucket) {
    return getBucketSchema(bucket, false);
  }

  public <B> Map<String, Object> getBucketSchema(B bucket, boolean listKeys) {
    RestTemplate restTemplate = getRestTemplate();
    ResponseEntity<Map> resp = restTemplate.getForEntity(defaultUri,
        Map.class,
        bucket,
        (listKeys ? "?keys=true" : ""));
    if (resp.hasBody()) {
      return resp.getBody();
    } else {
      throw new DataStoreOperationException("Error encountered retrieving bucket schema (Status: " + resp.getStatusCode() + ")");
    }
  }

  public void afterPropertiesSet() throws Exception {
    Assert.notNull(conversionService, "Must specify a valid ConversionService.");
    if (null == bucketKeyResolvers) {
      bucketKeyResolvers = new ArrayList<BucketKeyResolver>();
      bucketKeyResolvers.add(new SimpleBucketKeyResolver());
    }

    if (groovyPresent) {
      // Native conversion for Groovy GString objects
      ObjectMapper mapper = new ObjectMapper();
      CustomSerializerFactory fac = new CustomSerializerFactory();
      fac.addSpecificMapping(GStringImpl.class, ToStringSerializer.instance);
      mapper.setSerializerFactory(fac);
      List<HttpMessageConverter<?>> converters = getRestTemplate().getMessageConverters();
      for (HttpMessageConverter converter : converters) {
        if (converter instanceof MappingJacksonHttpMessageConverter) {
          ((MappingJacksonHttpMessageConverter) converter).setObjectMapper(mapper);
        }
      }
    }
  }


  /*----------------- Utilities -----------------*/

  protected BucketKeyPair resolveBucketKeyPair(Object key, Object val) {
    BucketKeyResolver resolver = null;
    for (BucketKeyResolver r : bucketKeyResolvers) {
      if (r.canResolve(key)) {
        resolver = r;
        break;
      }
    }
    BucketKeyPair bucketKeyPair;
    if (null != resolver) {
      bucketKeyPair = resolver.resolve(key);
      if (null != val) {
        Annotation meta = (val instanceof Class ? (Class) val : val.getClass()).getAnnotation(KeyValueStoreMetaData.class);
        if (null != meta) {
          String bucket = ((KeyValueStoreMetaData) meta).bucket();
          if (null != bucket) {
            return new SimpleBucketKeyPair<String, Object>(bucket, bucketKeyPair.getKey());
          }
        }
      }
      return bucketKeyPair;
    }
    throw new DataStoreOperationException(String.format("No resolvers available to resolve bucket/key pair from %s",
        key));
  }

  protected MediaType extractMediaType(Object value) {
    MediaType mediaType = (value instanceof byte[] ? MediaType.APPLICATION_OCTET_STREAM : MediaType.APPLICATION_JSON);
    if (value.getClass().getAnnotations().length > 0) {
      KeyValueStoreMetaData meta = value.getClass().getAnnotation(KeyValueStoreMetaData.class);
      if (null != meta) {
        mediaType = MediaType.parseMediaType(meta.mediaType());
      }
    }
    return mediaType;
  }

  protected RiakMetaData extractMetaData(HttpHeaders headers) throws IOException {
    Map<String, Object> props = new LinkedHashMap<String, Object>();
    for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
      List<String> val = entry.getValue();
      Object prop = (1 == val.size() ? val.get(0) : val);
      try {
        if (entry.getKey().equals("Last-Modified") || entry.getKey().equals("Date")) {
          prop = httpDate.parse(val.get(0));
        }
      } catch (ParseException e) {
        log.error(e.getMessage(), e);
      }

      if (entry.getKey().equals("Link")) {
        List<String> links = new ArrayList<String>();
        for (String link : entry.getValue()) {
          String[] parts = link.split(",");
          for (String part : parts) {
            String s = part.replaceAll("<(.+)>; rel=\"(\\S+)\"[,]?", "").trim();
            if (!"".equals(s)) {
              links.add(s);
            }
          }
        }
        props.put("Link", links);
      } else {
        props.put(entry.getKey().toString(), prop);
      }
    }
    props.put("ETag", headers.getETag());
    RiakMetaData meta = new RiakMetaData(headers.getContentType(), props);

    return meta;
  }


  protected <K, T> T checkCache(K key, Class<T> requiredType) {
    BucketKeyPair bucketKeyPair = resolveBucketKeyPair(key, requiredType);
    RiakValue<?> obj = cache.get(bucketKeyPair);
    if (null != obj) {
      String bucketName = (null != bucketKeyPair.getBucket() ? bucketKeyPair.getBucket()
          .toString() : requiredType.getName());
      RestTemplate restTemplate = getRestTemplate();
      HttpHeaders resp = restTemplate.headForHeaders(defaultUri, bucketName, bucketKeyPair.getKey());
      if (!obj.getMetaData().getProperties().get("ETag").toString().equals(resp.getETag())) {
        obj = null;
      } else {
        if (log.isDebugEnabled()) {
          log.debug("Returning CACHED object: " + obj);
        }
      }
    }
    return (null != obj ? (T) obj.get() : null);
  }

  public String extractPrefix() {
    Matcher m = prefix.matcher(defaultUri);
    if (m.matches()) {
      return "/" + m.group(3);
    }
    return "/riak";
  }

}