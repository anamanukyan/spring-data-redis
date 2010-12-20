/*
 * Copyright (c) 2010 by J. Brisbin <jon@jbrisbin.com>
 * Portions (c) 2010 by NPC International, Inc. or the
 * original author(s).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.data.keyvalue.riak.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.keyvalue.riak.DataStoreOperationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * @author J. Brisbin <jon@jbrisbin.com>
 */
public class AsyncRiakTemplate extends AbstractRiakTemplate implements AsyncBucketKeyValueStoreOperations {

  protected final Logger log = LoggerFactory.getLogger(getClass());

  protected ExecutorService workerPool = Executors.newCachedThreadPool();
  protected AsyncKeyValueStoreOperation<Throwable> defaultErrorHandler = new LoggingErrorHandler();

  public AsyncRiakTemplate() {
    super();
  }

  public AsyncRiakTemplate(ClientHttpRequestFactory requestFactory) {
    super(requestFactory);
  }

  public ExecutorService getWorkerPool() {
    return workerPool;
  }

  public void setWorkerPool(ExecutorService workerPool) {
    this.workerPool = workerPool;
  }

  public AsyncKeyValueStoreOperation<Throwable> getDefaultErrorHandler() {
    return defaultErrorHandler;
  }

  public void setDefaultErrorHandler(AsyncKeyValueStoreOperation<Throwable> defaultErrorHandler) {
    this.defaultErrorHandler = defaultErrorHandler;
  }

  public <B, K, V> Future<?> set(B bucket, K key, V value, AsyncKeyValueStoreOperation<V> callback) {
    return setWithMetaData(bucket, key, value, null, null, callback);
  }

  public <B, K, V> Future<?> set(B bucket, K key, V value, QosParameters qosParams, AsyncKeyValueStoreOperation<V> callback) {
    return setWithMetaData(bucket, key, value, null, qosParams, callback);
  }

  public <B, K> Future<?> setAsBytes(B bucket, K key, byte[] value, AsyncKeyValueStoreOperation<byte[]> callback) {
    return setWithMetaData(bucket, key, value, null, null, callback);
  }

  @SuppressWarnings({"unchecked"})
  public <B, K, V> Future<V> setWithMetaData(B bucket, K key, V value, Map<String, String> metaData, QosParameters qosParams, AsyncKeyValueStoreOperation<V> callback) {
    String bucketName = (null != bucket ? bucket.toString() : value.getClass().getName());
    // Get a key name that may or may not include the QOS parameters.
    Assert.notNull(key, "Cannot use a <NULL> key.");
    String keyName = (null != qosParams ? key.toString() + extractQosParameters(qosParams) : key
        .toString());
    HttpHeaders headers = defaultHeaders(metaData);
    headers.setContentType(extractMediaType(value));
    headers.set(RIAK_META_CLASSNAME, value.getClass().getName());
    HttpEntity<V> entity = new HttpEntity<V>(value, headers);
    return (Future<V>) workerPool.submit(new AsyncPost<V>(bucketName,
        keyName,
        entity,
        callback));
  }

  public <B, K, V> Future<?> get(B bucket, K key, AsyncKeyValueStoreOperation<V> callback) {
    return getWithMetaData(bucket, key, null, callback);
  }

  @SuppressWarnings({"unchecked"})
  public <B, K, T> Future<?> getWithMetaData(B bucket, K key, Class<T> requiredType, AsyncKeyValueStoreOperation<T> callback) {
    String bucketName = (null != bucket ? bucket.toString() : requiredType.getName());
    // Get a key name that may or may not include the QOS parameters.
    Assert.notNull(key, "Cannot use a <NULL> key.");
    if (null == requiredType) {
      try {
        requiredType = (Class<T>) getType(bucketName, key.toString());
      } catch (ClassNotFoundException e) {
        throw new DataStoreOperationException(e.getMessage(), e);
      }
    }
    return workerPool.submit(new AsyncGet<T>(bucketName,
        key.toString(),
        requiredType,
        callback));
  }

  public <B, K> Future<?> getAsBytes(B bucket, K key, AsyncKeyValueStoreOperation<byte[]> callback) {
    return getWithMetaData(bucket, key, byte[].class, callback);
  }

  public <B, K, T> Future<?> getAsType(B bucket, K key, Class<T> requiredType, AsyncKeyValueStoreOperation<T> callback) {
    return getWithMetaData(bucket, key, requiredType, callback);
  }

  public <B, K, V> Future<?> getAndSet(final B bucket, final K key, final V value, final AsyncKeyValueStoreOperation<V> callback) {
    final List<Future<?>> futures = new ArrayList<Future<?>>();
    try {
      getWithMetaData(bucket, key, null, new AsyncKeyValueStoreOperation<Object>() {
        @SuppressWarnings({"unchecked"})
        public void completed(KeyValueStoreMetaData meta, Object result) {
          futures.add(setWithMetaData(bucket, key, value, null, null, null));
          callback.completed(meta, (V) result);
        }

        public void failed(Throwable error) {
          callback.failed(error);
        }
      }).get();
    } catch (InterruptedException e) {
      log.error(e.getMessage(), e);
    } catch (ExecutionException e) {
      log.error(e.getMessage(), e);
    }
    return futures.size() > 0 ? futures.get(0) : null;
  }

  public <B, K> Future<?> getAndSetAsBytes(B bucket, K key, byte[] value, AsyncKeyValueStoreOperation<byte[]> callback) {
    return getAndSet(bucket, key, value, callback);
  }

  public <B, K, V, T> Future<?> getAndSetAsType(final B bucket, final K key, final V value, final Class<T> requiredType, final AsyncKeyValueStoreOperation<V> callback) {
    final List<Future<?>> futures = new ArrayList<Future<?>>();
    getWithMetaData(bucket, key, requiredType, new AsyncKeyValueStoreOperation<T>() {
      @SuppressWarnings({"unchecked"})
      public void completed(KeyValueStoreMetaData meta, T result) {
        futures.add(setWithMetaData(bucket, key, value, null, null, null));
        callback.completed(meta, (V) result);
      }

      public void failed(Throwable error) {
        callback.failed(error);
      }
    });
    return futures.size() > 0 ? futures.get(0) : null;
  }

  public <B, K, V> Future<?> setIfKeyNonExistent(final B bucket, final K key, final V value, final AsyncKeyValueStoreOperation<V> callback) {
    return containsKey(bucket, key, new AsyncKeyValueStoreOperation<Boolean>() {
      public void completed(KeyValueStoreMetaData meta, Boolean result) {
        if (!result) {
          setWithMetaData(bucket, key, value, null, null, callback);
        }
      }

      public void failed(Throwable error) {
        callback.failed(error);
      }
    });
  }

  public <B, K> Future<?> setIfKeyNonExistentAsBytes(final B bucket, final K key, final byte[] value, final AsyncKeyValueStoreOperation<byte[]> callback) {
    return containsKey(bucket, key, new AsyncKeyValueStoreOperation<Boolean>() {
      public void completed(KeyValueStoreMetaData meta, Boolean result) {
        if (!result) {
          setWithMetaData(bucket, key, value, null, null, callback);
        }
      }

      public void failed(Throwable error) {
        callback.failed(error);
      }
    });
  }

  public <B, K> Future<?> containsKey(B bucket, K key, final AsyncKeyValueStoreOperation<Boolean> callback) {
    Assert.notNull(bucket, "Bucket cannot be null when checking for existence.");
    Assert.notNull(key, "Key cannot be null when checking for existence");
    return workerPool.submit(new AsyncHead(bucket.toString(),
        key.toString(),
        new AsyncKeyValueStoreOperation<HttpHeaders>() {
          public void completed(KeyValueStoreMetaData meta, HttpHeaders result) {
            callback.completed(null, (null != result));
          }

          public void failed(Throwable error) {
            callback.failed(error);
          }
        }));
  }

  public <B, K> Future<?> delete(B bucket, K key, AsyncKeyValueStoreOperation<Boolean> callback) {
    Assert.notNull(bucket, "Bucket cannot be null when deleting.");
    Assert.notNull(key, "Key cannot be null when deleting.");
    return workerPool.submit(new AsyncDelete(bucket.toString(), key.toString(), callback));
  }

  public <B, K> Future<?> setAsBytes(B bucket, K key, byte[] value, QosParameters qosParams, AsyncKeyValueStoreOperation<byte[]> callback) {
    return setWithMetaData(bucket, key, value, null, qosParams, callback);
  }

  public <B, K, V> Future<?> setWithMetaData(B bucket, K key, V value, Map<String, String> metaData, AsyncKeyValueStoreOperation<V> callback) {
    return setWithMetaData(bucket, key, value, metaData, null, callback);
  }

  protected Class<?> getType(String bucket, String key) throws ClassNotFoundException {
    HttpHeaders headers = getRestTemplate().headForHeaders(defaultUri, bucket, key);
    Class<?> clazz = null;
    if (null != headers) {
      String s = headers.getFirst(RIAK_META_CLASSNAME);
      if (null != s) {
        try {
          clazz = Class.forName(s);
        } catch (ClassNotFoundException ignored) {
          if (headers.getContentType().equals(MediaType.APPLICATION_JSON)) {
            clazz = Map.class;
          } else if (headers.getContentType().equals(MediaType.TEXT_PLAIN)) {
            clazz = String.class;
          } else {
            // handle as bytes
            log.error("Need to handle bytes!");
          }
        }
      }
    }
    if (null == clazz) {
      clazz = byte[].class;
    }
    return clazz;
  }

  protected class AsyncPost<V> implements Runnable {

    private String bucket;
    private String key;
    private HttpEntity<V> entity = null;
    private AsyncKeyValueStoreOperation<V> callback = null;

    public AsyncPost(String bucket, String key, HttpEntity<V> entity, AsyncKeyValueStoreOperation<V> callback) {
      this.bucket = bucket;
      this.key = key;
      this.entity = entity;
      this.callback = callback;
    }

    @SuppressWarnings({"unchecked"})
    public void run() {
      try {
        HttpEntity<?> result = getRestTemplate().postForEntity(defaultUri,
            entity,
            (entity.getBody() instanceof byte[] ? byte[].class : entity.getBody().getClass()),
            bucket,
            key + "?returnbody=true");
        if (log.isDebugEnabled()) {
          log.debug(String.format("PUT object: bucket=%s, key=%s, value=%s",
              bucket,
              key,
              entity));
        }
        if (null != callback) {
          callback.completed(extractMetaData(result.getHeaders()), (V) result.getBody());
        }
      } catch (Throwable t) {
        DataStoreOperationException dsoe = new DataStoreOperationException(t.getMessage(), t);
        if (null != callback) {
          callback.failed(dsoe);
        } else {
          defaultErrorHandler.failed(dsoe);
        }
      }
    }

  }

  protected class AsyncGet<T> implements Runnable {

    private String bucket;
    private String key;
    private Class<T> requiredType;
    private AsyncKeyValueStoreOperation<T> callback = null;

    public AsyncGet(String bucket, String key, Class<T> requiredType, AsyncKeyValueStoreOperation<T> callback) {
      this.bucket = bucket;
      this.key = key;
      this.requiredType = requiredType;
      this.callback = callback;
    }

    public void run() {
      try {
        ResponseEntity<T> result = getRestTemplate().getForEntity(defaultUri,
            requiredType,
            bucket,
            key);
        if (result.hasBody()) {
          RiakMetaData meta = extractMetaData(result.getHeaders());
          RiakValue<T> val = new RiakValue<T>(result.getBody(), meta);
          if (useCache) {
            cache.put(new SimpleBucketKeyPair<Object, Object>(bucket, key), val);
          }
          if (null != callback) {
            callback.completed(meta, val.get());
          }
          if (log.isDebugEnabled()) {
            log.debug(String.format("GET object: bucket=%s, key=%s, type=%s",
                bucket,
                key,
                requiredType.getName()));
          }
        }
      } catch (Throwable t) {
        DataStoreOperationException dsoe = new DataStoreOperationException(t.getMessage(), t);
        if (null != callback) {
          callback.failed(dsoe);
        } else {
          defaultErrorHandler.failed(dsoe);
        }
      }
    }
  }

  protected class AsyncHead implements Runnable {

    private String bucket;
    private String key;
    private AsyncKeyValueStoreOperation<HttpHeaders> callback = null;

    public AsyncHead(String bucket, String key, AsyncKeyValueStoreOperation<HttpHeaders> callback) {
      this.bucket = bucket;
      this.key = key;
      this.callback = callback;
    }

    public void run() {
      try {
        HttpHeaders headers = getRestTemplate().headForHeaders(defaultUri, bucket, key);
        if (null != headers) {
          if (null != callback) {
            callback.completed(null, headers);
          }
        }
      } catch (Throwable t) {
        DataStoreOperationException dsoe = new DataStoreOperationException(t.getMessage(), t);
        if (null != callback) {
          callback.failed(dsoe);
        } else {
          defaultErrorHandler.failed(dsoe);
        }
      }
    }
  }

  protected class AsyncDelete implements Runnable {

    private String bucket;
    private String key;
    private AsyncKeyValueStoreOperation<Boolean> callback = null;

    public AsyncDelete(String bucket, String key, AsyncKeyValueStoreOperation<Boolean> callback) {
      this.bucket = bucket;
      this.key = key;
      this.callback = callback;
    }

    public void run() {
      try {
        getRestTemplate().delete(defaultUri, bucket, key);
        if (null != callback) {
          callback.completed(null, true);
        }
      } catch (Throwable t) {
        DataStoreOperationException dsoe = new DataStoreOperationException(t.getMessage(), t);
        if (null != callback) {
          callback.failed(dsoe);
        } else {
          defaultErrorHandler.failed(dsoe);
        }
      }
    }
  }

  protected class LoggingErrorHandler implements AsyncKeyValueStoreOperation<Throwable> {
    public void completed(KeyValueStoreMetaData meta, Throwable result) {
    }

    public void failed(Throwable error) {
      log.error(error.getMessage(), error);
    }
  }

}