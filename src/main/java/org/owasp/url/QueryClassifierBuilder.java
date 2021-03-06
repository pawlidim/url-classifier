// Copyright (c) 2017, Mike Samuel
// All rights reserved.
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions
// are met:
//
// Redistributions of source code must retain the above copyright
// notice, this list of conditions and the following disclaimer.
// Redistributions in binary form must reproduce the above copyright
// notice, this list of conditions and the following disclaimer in the
// documentation and/or other materials provided with the distribution.
// Neither the name of the OWASP nor the names of its contributors may
// be used to endorse or promote products derived from this software
// without specific prior written permission.
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
// "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
// LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
// FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
// COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
// INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
// BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
// LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
// CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
// LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN
// ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
// POSSIBILITY OF SUCH DAMAGE.

package org.owasp.url;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;

/**
 * Builds a classifier over URL queries.
 *
 * <p>The operators below operate on a query string like
 * "{@code ?key0=value0&key1=value1}" after it has been decomposed into
 * a sequence of decoded key/value pairs.
 *
 * <p>For example, the query "{@code ?a=b%20c&a=d&e}" specifies the
 * key value pairs {@code [("a", "b c"), ("a", "d"), ("e", absent)]}.
 *
 * @see QueryClassifiers#builder
 */
public final class QueryClassifierBuilder {
  private ImmutableSet.Builder<String> mayKeys = ImmutableSet.builder();
  private Predicate<? super String> mayClassifier;
  private ImmutableSet.Builder<String> onceKeys = ImmutableSet.builder();
  private Predicate<? super String> onceClassifier;
  private ImmutableSet.Builder<String> mustKeys = ImmutableSet.builder();
  private Map<String, Predicate<? super Optional<String>>> valueClassifiers =
      Maps.newLinkedHashMap();

  QueryClassifierBuilder() {
    // Use static factory
  }

  /**
   * Builds a classifier based on previous allow/match decisions.
   * This may be reused after a call to build and subsequent calls to
   * allow/match methods will not affect previously built classifiers.
   */
  public QueryClassifier build() {
    ImmutableSet<String> mayKeySet = mayKeys.build();
    Predicate<? super String> mayKeyClassifier;
    if (mayClassifier != null) {
      mayKeyClassifier = mayClassifier;
    } else if (mayKeySet.isEmpty()) {
        // If nothing specified, assume permissive.
      mayKeyClassifier = Predicates.alwaysTrue();
    } else {
      // If a set specified, defer to the set.
      mayKeyClassifier = Predicates.<String>alwaysFalse();
    }

    ImmutableSet<String> onceKeySet = onceKeys.build();
    Predicate<? super String> onceKeyClassifier;
    if (onceClassifier != null) {
      onceKeyClassifier = onceClassifier;
    } else {
      onceKeyClassifier = Predicates.<String>alwaysFalse();
    }

    ImmutableSet<String> mustKeySet = mustKeys.build();
    ImmutableMap<String, Predicate<? super Optional<String>>> valueClassifierMap =
        ImmutableMap.copyOf(valueClassifiers);

    // If something may appear once or must appear, then it may appear.
    if (!Predicates.alwaysTrue().equals(mayKeyClassifier)) {
      if (!Predicates.alwaysFalse().equals(onceKeyClassifier)) {
        mayKeyClassifier = Predicates.or(mayKeyClassifier, onceKeyClassifier);
      }
      mayKeySet = ImmutableSet.<String>builder()
          .addAll(mayKeySet)
          .addAll(onceKeySet)
          .addAll(mustKeySet)
          .build();
    }

    return new QueryClassifierImpl(
        mayKeySet,
        mayKeyClassifier,
        onceKeySet,
        onceKeyClassifier,
        mustKeySet,
        valueClassifierMap);
  }

  /**
   * Specify the keys that MAY appear -- all keys must match.
   * If no variant of {@link #mayHaveKeys} is called,
   * then any keys may appear.
   * Multiple calls union.
   *
   * <p>All variants of this method operate on keys post-percent decoding.
   */
  public QueryClassifierBuilder mayHaveKeys(String... keys) {
    return mayHaveKeys(Arrays.asList(keys));
  }
  /** @see #mayHaveKeys(String...) */
  public QueryClassifierBuilder mayHaveKeys(Iterable<? extends String> keys) {
    this.mayKeys.addAll(keys);
    return this;
  }
  /** @see #mayHaveKeys(String...) */
  public QueryClassifierBuilder mayHaveKeys(Predicate<? super String> p) {
    if (mayClassifier == null) {
      mayClassifier = p;
    } else {
      mayClassifier = Predicates.<String>or(mayClassifier, p);
    }
    return this;
  }

  /**
   * Specifies that the keys may not appear more than once.
   *
   * <p>All variants of this method operate on keys post-percent decoding.
   */
  public QueryClassifierBuilder mayNotRepeatKeys(String... keys) {
    return mayNotRepeatKeys(Arrays.asList(keys));
  }
  /** @see #mayNotRepeatKeys(String...) */
  public QueryClassifierBuilder mayNotRepeatKeys(Iterable<? extends String> keys) {
    this.onceKeys.addAll(keys);
    return this;
  }
  /** @see #mayNotRepeatKeys(String...) */
  public QueryClassifierBuilder mayNotRepeatKeys(Predicate<? super String> p) {
    if (onceClassifier == null) {
      onceClassifier = p;
    } else {
      onceClassifier = Predicates.or(onceClassifier, p);
    }
    return this;
  }

  /**
   * Specify which keys MUST appear ignoring order.
   * Does not match if any of the specified keys are missing.
   */
  public QueryClassifierBuilder mustHaveKeys(String... keys) {
    return mustHaveKeys(Arrays.asList(keys));
  }
  /** @see #mustHaveKeys(String...) */
  public QueryClassifierBuilder mustHaveKeys(Iterable<? extends String> keys) {
    mustKeys.addAll(keys);
    return this;
  }

  /**
   * Specify that any values associated with the given key must match the
   * given predicate.
   * <ul>
   *   <li>For valueMustMatch("foo", p) the URI "?foo=bar" will cause a call
   *       p.apply(of("bar")).
   *   <li>For valueMustMatch("foo", p) the URI "?foo=" will cause a call
   *       p.apply(of("")).
   *   <li>For valueMustMatch("foo", p) the URI "?foo" will cause a call
   *       p.apply(absent).
   * </ul>
   * <p>The value received by the predicate has been percent decoded.
   *
   * <p>This does not require that key appear.  If key appears multiple
   * times, the predicate will be applied to each value in the order
   * it appears.
   */
  public QueryClassifierBuilder valueMustMatch(
      String key,
      Predicate<? super Optional<String>> valueClassifier) {
    Predicate<? super Optional<String>> old = valueClassifiers.put(
        Preconditions.checkNotNull(key),
        Preconditions.checkNotNull(valueClassifier));
    if (old != null) {
      valueClassifiers.put(
          key,
          Predicates.and(old, valueClassifier));
    }
    return this;
  }
}

final class QueryClassifierImpl implements QueryClassifier {
  private final ImmutableSet<String> mayKeySet;
  private final Predicate<? super String> mayKeyClassifier;
  private final ImmutableSet<String> onceKeySet;
  private final Predicate<? super String> onceKeyClassifier;
  private final ImmutableSet<String> mustKeySet;
  private final ImmutableMap<String, Predicate<? super Optional<String>>> valueClassifierMap;


  public QueryClassifierImpl(
      ImmutableSet<String> mayKeySet, Predicate<? super String> mayKeyClassifier,
      ImmutableSet<String> onceKeySet, Predicate<? super String> onceKeyClassifier,
      ImmutableSet<String> mustKeySet,
      ImmutableMap<String, Predicate<? super Optional<String>>> valueClassifierMap) {
    this.mayKeySet = mayKeySet;
    this.mayKeyClassifier = mayKeyClassifier;
    this.onceKeySet = onceKeySet;
    this.onceKeyClassifier = onceKeyClassifier;
    this.mustKeySet = mustKeySet;
    this.valueClassifierMap = valueClassifierMap;
  }

  enum Diagnostics implements Diagnostic {
    DISALLOWED_KEY,
    DISALLOWED_QUERY_KEY,
    DISALLOWED_QUERY_KEY_REPETITION,
    DISALLOWED_QUERY_VALUE,
    MISSING_REQUIRED_QUERY_KEY,
  }

  @Override
  public Classification apply(UrlValue x, Diagnostic.Receiver<? super UrlValue> r) {
    Set<String> keysSeen = new HashSet<String>();
    String query = x.getQuery();

    Classification result = Classification.MATCH;
    if (query != null) {
      int len = query.length();
      int eq = -1;
      int start = 0;
      char delim = '?';
      for (int i = 0; i <= len; ++i) {
        char c;
        if (i == len || (c = query.charAt(i)) == delim) {
          if (start < i) {
            int keyStart = start;
            int keyEnd = eq >= 0 ? eq : i;
            Optional<CharSequence> keyOpt = Percent.decode(
                query, keyStart, keyEnd, true);
            if (!keyOpt.isPresent()) { return Classification.INVALID; }
            String key = keyOpt.get().toString();
            Optional<CharSequence> valueOpt = Optional.absent();
            if (eq >= 0) {
              valueOpt = Percent.decode(query, eq + 1, i, true);
              if (!valueOpt.isPresent()) { return Classification.INVALID; }
            }
            if (result == Classification.MATCH) {
              if (!mayKeyClassifier.apply(key) && !mayKeySet.contains(key)) {
                r.note(Diagnostics.DISALLOWED_QUERY_KEY, x);
                result = Classification.NOT_A_MATCH;
              } else if (
                  !keysSeen.add(key)
                  && (onceKeyClassifier.apply(key)
                      || onceKeySet.contains(key))) {
                result = Classification.NOT_A_MATCH;
                r.note(Diagnostics.DISALLOWED_QUERY_KEY_REPETITION, x);
              } else {
                Predicate<? super Optional<String>> p = this.valueClassifierMap.get(key);
                if (p != null) {
                  Optional<String> value = valueOpt.isPresent()
                      ? Optional.of(valueOpt.get().toString())
                      : Optional.<String>absent();
                  if (!p.apply(value)) {
                    result = Classification.NOT_A_MATCH;
                    r.note(Diagnostics.DISALLOWED_QUERY_VALUE, x);
                  }
                }
              }
            }
          }
          start = i + 1;
          eq = -1;
        } else if (c == '=' && eq == -1) {
          eq = i;
        }
        delim = '&';
      }
    }

    if (result == Classification.MATCH && !keysSeen.containsAll(mustKeySet)) {
      r.note(Diagnostics.MISSING_REQUIRED_QUERY_KEY, x);
      result = Classification.NOT_A_MATCH;
    }
    return result;
  }

}
