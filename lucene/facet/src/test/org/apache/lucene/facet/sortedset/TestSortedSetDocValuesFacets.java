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
package org.apache.lucene.facet.sortedset;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.facet.DrillDownQuery;
import org.apache.lucene.facet.FacetResult;
import org.apache.lucene.facet.FacetTestCase;
import org.apache.lucene.facet.Facets;
import org.apache.lucene.facet.FacetsCollector;
import org.apache.lucene.facet.FacetsConfig;
import org.apache.lucene.facet.LabelAndValue;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.RandomIndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.Accountable;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.LuceneTestCase;
import org.apache.lucene.util.NamedThreadFactory;
import org.apache.lucene.util.TestUtil;

public class TestSortedSetDocValuesFacets extends FacetTestCase {

  // NOTE: TestDrillSideways.testRandom also sometimes
  // randomly uses SortedSetDV

  public void testBasic() throws Exception {
    Directory dir = newDirectory();

    FacetsConfig config = new FacetsConfig();
    config.setMultiValued("a", true);
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);

    Document doc = new Document();
    doc.add(new SortedSetDocValuesFacetField("a", "foo"));
    doc.add(new SortedSetDocValuesFacetField("a", "bar"));
    doc.add(new SortedSetDocValuesFacetField("a", "zoo"));
    doc.add(new SortedSetDocValuesFacetField("b", "baz"));
    writer.addDocument(config.build(doc));
    if (random().nextBoolean()) {
      writer.commit();
    }

    doc = new Document();
    doc.add(new SortedSetDocValuesFacetField("a", "foo"));
    writer.addDocument(config.build(doc));

    // NRT open
    IndexSearcher searcher = newSearcher(writer.getReader());

    // Per-top-reader state:
    SortedSetDocValuesReaderState state =
        new DefaultSortedSetDocValuesReaderState(searcher.getIndexReader());

    ExecutorService exec = randomExecutorServiceOrNull();
    Facets facets = getAllFacets(searcher, state, exec);

    assertEquals(
        "dim=a path=[] value=4 childCount=3\n  foo (2)\n  bar (1)\n  zoo (1)\n",
        facets.getTopChildren(10, "a").toString());
    assertEquals(
        "dim=b path=[] value=1 childCount=1\n  baz (1)\n",
        facets.getTopChildren(10, "b").toString());

    // DrillDown:
    DrillDownQuery q = new DrillDownQuery(config);
    q.add("a", "foo");
    q.add("b", "baz");
    TopDocs hits = searcher.search(q, 1);
    assertEquals(1, hits.totalHits.value);

    if (exec != null) {
      exec.shutdownNow();
    }
    writer.close();
    IOUtils.close(searcher.getIndexReader(), dir);
  }

  // See: LUCENE-10070
  public void testCountAll() throws Exception {
    Directory dir = newDirectory();

    FacetsConfig config = new FacetsConfig();
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);

    Document doc = new Document();
    doc.add(new StringField("id", "0", Field.Store.NO));
    doc.add(new SortedSetDocValuesFacetField("a", "foo"));
    writer.addDocument(config.build(doc));

    doc = new Document();
    doc.add(new StringField("id", "1", Field.Store.NO));
    doc.add(new SortedSetDocValuesFacetField("a", "bar"));
    writer.addDocument(config.build(doc));

    writer.deleteDocuments(new Term("id", "0"));

    // NRT open
    IndexSearcher searcher = newSearcher(writer.getReader());

    // Per-top-reader state:
    SortedSetDocValuesReaderState state =
        new DefaultSortedSetDocValuesReaderState(searcher.getIndexReader());

    Facets facets = new SortedSetDocValuesFacetCounts(state);

    assertEquals(
        "dim=a path=[] value=1 childCount=1\n  bar (1)\n",
        facets.getTopChildren(10, "a").toString());

    ExecutorService exec =
        new ThreadPoolExecutor(
            1,
            TestUtil.nextInt(random(), 2, 6),
            Long.MAX_VALUE,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<Runnable>(),
            new NamedThreadFactory("TestIndexSearcher"));

    facets = new ConcurrentSortedSetDocValuesFacetCounts(state, exec);

    assertEquals(
        "dim=a path=[] value=1 childCount=1\n  bar (1)\n",
        facets.getTopChildren(10, "a").toString());

    writer.close();
    IOUtils.close(searcher.getIndexReader(), dir);
    exec.shutdownNow();
  }

  public void testBasicSingleValued() throws Exception {
    Directory dir = newDirectory();

    FacetsConfig config = new FacetsConfig();
    config.setMultiValued("a", false);
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);

    Document doc = new Document();
    doc.add(new SortedSetDocValuesFacetField("a", "foo"));
    doc.add(new SortedSetDocValuesFacetField("b", "bar"));
    writer.addDocument(config.build(doc));
    doc = new Document();
    doc.add(new SortedSetDocValuesFacetField("a", "foo"));
    writer.addDocument(config.build(doc));
    if (random().nextBoolean()) {
      writer.commit();
    }

    doc = new Document();
    doc.add(new SortedSetDocValuesFacetField("a", "baz"));
    writer.addDocument(config.build(doc));

    // NRT open
    IndexSearcher searcher = newSearcher(writer.getReader());

    // Per-top-reader state:
    SortedSetDocValuesReaderState state =
        new DefaultSortedSetDocValuesReaderState(searcher.getIndexReader());

    ExecutorService exec = randomExecutorServiceOrNull();
    Facets facets = getAllFacets(searcher, state, exec);

    assertEquals(
        "dim=a path=[] value=3 childCount=2\n  foo (2)\n  baz (1)\n",
        facets.getTopChildren(10, "a").toString());
    assertEquals(
        "dim=b path=[] value=1 childCount=1\n  bar (1)\n",
        facets.getTopChildren(10, "b").toString());

    // DrillDown:
    DrillDownQuery q = new DrillDownQuery(config);
    q.add("a", "foo");
    q.add("b", "bar");
    TopDocs hits = searcher.search(q, 1);
    assertEquals(1, hits.totalHits.value);

    if (exec != null) {
      exec.shutdownNow();
    }
    writer.close();
    IOUtils.close(searcher.getIndexReader(), dir);
  }

  public void testDrillDownOptions() throws Exception {
    Directory dir = newDirectory();

    FacetsConfig config = new FacetsConfig();
    config.setDrillDownTermsIndexing("c", FacetsConfig.DrillDownTermsIndexing.NONE);
    config.setDrillDownTermsIndexing(
        "d", FacetsConfig.DrillDownTermsIndexing.DIMENSION_AND_FULL_PATH);
    config.setDrillDownTermsIndexing("e", FacetsConfig.DrillDownTermsIndexing.ALL_PATHS_NO_DIM);
    config.setDrillDownTermsIndexing("f", FacetsConfig.DrillDownTermsIndexing.FULL_PATH_ONLY);
    config.setDrillDownTermsIndexing("g", FacetsConfig.DrillDownTermsIndexing.ALL);
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);

    Document doc = new Document();
    doc.add(new SortedSetDocValuesFacetField("c", "foo"));
    doc.add(new SortedSetDocValuesFacetField("d", "foo"));
    doc.add(new SortedSetDocValuesFacetField("e", "foo"));
    doc.add(new SortedSetDocValuesFacetField("f", "foo"));
    doc.add(new SortedSetDocValuesFacetField("g", "foo"));
    writer.addDocument(config.build(doc));
    if (random().nextBoolean()) {
      writer.commit();
    }

    doc = new Document();
    doc.add(new SortedSetDocValuesFacetField("a", "foo"));
    writer.addDocument(config.build(doc));

    // NRT open
    IndexSearcher searcher = newSearcher(writer.getReader());
    ExecutorService exec = randomExecutorServiceOrNull();

    // Drill down with different indexing configuration options
    DrillDownQuery q = new DrillDownQuery(config);
    q.add("c");
    TopDocs hits = searcher.search(q, 1);
    assertEquals(0, hits.totalHits.value);

    q = new DrillDownQuery(config);
    q.add("c", "foo");
    hits = searcher.search(q, 1);
    assertEquals(0, hits.totalHits.value);

    q = new DrillDownQuery(config);
    q.add("d");
    hits = searcher.search(q, 1);
    assertEquals(1, hits.totalHits.value);

    q = new DrillDownQuery(config);
    q.add("d", "foo");
    hits = searcher.search(q, 1);
    assertEquals(1, hits.totalHits.value);

    q = new DrillDownQuery(config);
    q.add("e");
    hits = searcher.search(q, 1);
    assertEquals(0, hits.totalHits.value);

    q = new DrillDownQuery(config);
    q.add("e", "foo");
    hits = searcher.search(q, 1);
    assertEquals(1, hits.totalHits.value);

    q = new DrillDownQuery(config);
    q.add("f");
    hits = searcher.search(q, 1);
    assertEquals(0, hits.totalHits.value);

    q = new DrillDownQuery(config);
    q.add("f", "foo");
    hits = searcher.search(q, 1);
    assertEquals(1, hits.totalHits.value);

    q = new DrillDownQuery(config);
    q.add("g");
    hits = searcher.search(q, 1);
    assertEquals(1, hits.totalHits.value);

    q = new DrillDownQuery(config);
    q.add("g", "foo");
    hits = searcher.search(q, 1);
    assertEquals(1, hits.totalHits.value);

    if (exec != null) {
      exec.shutdownNow();
    }
    writer.close();
    IOUtils.close(searcher.getIndexReader(), dir);
  }

  // LUCENE-5090
  @SuppressWarnings("unused")
  public void testStaleState() throws Exception {
    Directory dir = newDirectory();

    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);

    FacetsConfig config = new FacetsConfig();

    Document doc = new Document();
    doc.add(new SortedSetDocValuesFacetField("a", "foo"));
    writer.addDocument(config.build(doc));

    IndexReader r = writer.getReader();
    SortedSetDocValuesReaderState state = new DefaultSortedSetDocValuesReaderState(r);

    doc = new Document();
    doc.add(new SortedSetDocValuesFacetField("a", "bar"));
    writer.addDocument(config.build(doc));

    doc = new Document();
    doc.add(new SortedSetDocValuesFacetField("a", "baz"));
    writer.addDocument(config.build(doc));

    IndexSearcher searcher = newSearcher(writer.getReader());

    FacetsCollector c = new FacetsCollector();

    searcher.search(new MatchAllDocsQuery(), c);

    expectThrows(
        IllegalStateException.class,
        () -> {
          new SortedSetDocValuesFacetCounts(state, c);
        });

    r.close();
    writer.close();
    searcher.getIndexReader().close();
    dir.close();
  }

  // LUCENE-5333
  public void testSparseFacets() throws Exception {
    Directory dir = newDirectory();

    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);

    FacetsConfig config = new FacetsConfig();

    Document doc = new Document();
    doc.add(new SortedSetDocValuesFacetField("a", "foo1"));
    writer.addDocument(config.build(doc));

    if (random().nextBoolean()) {
      writer.commit();
    }

    doc = new Document();
    doc.add(new SortedSetDocValuesFacetField("a", "foo2"));
    doc.add(new SortedSetDocValuesFacetField("b", "bar1"));
    writer.addDocument(config.build(doc));

    if (random().nextBoolean()) {
      writer.commit();
    }

    doc = new Document();
    doc.add(new SortedSetDocValuesFacetField("a", "foo3"));
    doc.add(new SortedSetDocValuesFacetField("b", "bar2"));
    doc.add(new SortedSetDocValuesFacetField("c", "baz1"));
    writer.addDocument(config.build(doc));

    // NRT open
    IndexSearcher searcher = newSearcher(writer.getReader());
    writer.close();

    // Per-top-reader state:
    SortedSetDocValuesReaderState state =
        new DefaultSortedSetDocValuesReaderState(searcher.getIndexReader());

    ExecutorService exec = randomExecutorServiceOrNull();
    Facets facets = getAllFacets(searcher, state, exec);

    // Ask for top 10 labels for any dims that have counts:
    List<FacetResult> results = facets.getAllDims(10);

    assertEquals(3, results.size());
    assertEquals(
        "dim=a path=[] value=3 childCount=3\n  foo1 (1)\n  foo2 (1)\n  foo3 (1)\n",
        results.get(0).toString());
    assertEquals(
        "dim=b path=[] value=2 childCount=2\n  bar1 (1)\n  bar2 (1)\n", results.get(1).toString());
    assertEquals("dim=c path=[] value=1 childCount=1\n  baz1 (1)\n", results.get(2).toString());

    Collection<Accountable> resources = state.getChildResources();
    assertTrue(state.toString().contains(FacetsConfig.DEFAULT_INDEX_FIELD_NAME));
    if (searcher.getIndexReader().leaves().size() > 1) {
      assertTrue(state.ramBytesUsed() > 0);
      assertFalse(resources.isEmpty());
      assertTrue(resources.toString().contains(FacetsConfig.DEFAULT_INDEX_FIELD_NAME));
    } else {
      assertEquals(0, state.ramBytesUsed());
      assertTrue(resources.isEmpty());
    }

    if (exec != null) {
      exec.shutdownNow();
    }
    searcher.getIndexReader().close();
    dir.close();
  }

  public void testSomeSegmentsMissing() throws Exception {
    Directory dir = newDirectory();

    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);

    FacetsConfig config = new FacetsConfig();

    Document doc = new Document();
    doc.add(new SortedSetDocValuesFacetField("a", "foo1"));
    writer.addDocument(config.build(doc));
    writer.commit();

    doc = new Document();
    writer.addDocument(config.build(doc));
    writer.commit();

    doc = new Document();
    doc.add(new SortedSetDocValuesFacetField("a", "foo2"));
    writer.addDocument(config.build(doc));
    writer.commit();

    // NRT open
    IndexSearcher searcher = newSearcher(writer.getReader());
    writer.close();

    // Per-top-reader state:
    SortedSetDocValuesReaderState state =
        new DefaultSortedSetDocValuesReaderState(searcher.getIndexReader());

    ExecutorService exec = randomExecutorServiceOrNull();
    Facets facets = getAllFacets(searcher, state, exec);

    // Ask for top 10 labels for any dims that have counts:
    assertEquals(
        "dim=a path=[] value=2 childCount=2\n  foo1 (1)\n  foo2 (1)\n",
        facets.getTopChildren(10, "a").toString());

    if (exec != null) {
      exec.shutdownNow();
    }
    searcher.getIndexReader().close();
    dir.close();
  }

  public void testRandom() throws Exception {
    int fullIterations = LuceneTestCase.TEST_NIGHTLY ? 20 : 3;
    for (int fullIter = 0; fullIter < fullIterations; fullIter++) {
      String[] tokens = getRandomTokens(10);
      Directory indexDir = newDirectory();
      Directory taxoDir = newDirectory();

      RandomIndexWriter w = new RandomIndexWriter(random(), indexDir);
      FacetsConfig config = new FacetsConfig();
      int numDocs = atLeast(1000);
      // Most of the time allow up to 7 dims per doc, but occasionally limit all docs to a single
      // dim:
      int numDims;
      if (random().nextInt(10) < 8) {
        numDims = TestUtil.nextInt(random(), 1, 7);
      } else {
        numDims = 1;
      }
      List<TestDoc> testDocs = getRandomDocs(tokens, numDocs, numDims);
      for (TestDoc testDoc : testDocs) {
        Document doc = new Document();
        doc.add(newStringField("content", testDoc.content, Field.Store.NO));
        for (int j = 0; j < numDims; j++) {
          if (testDoc.dims[j] != null) {
            doc.add(new SortedSetDocValuesFacetField("dim" + j, testDoc.dims[j]));
          }
        }
        w.addDocument(config.build(doc));
      }

      // NRT open
      IndexSearcher searcher = newSearcher(w.getReader());

      // Per-top-reader state:
      SortedSetDocValuesReaderState state =
          new DefaultSortedSetDocValuesReaderState(searcher.getIndexReader());
      ExecutorService exec = randomExecutorServiceOrNull();

      int iters = atLeast(100);
      for (int iter = 0; iter < iters; iter++) {
        String searchToken = tokens[random().nextInt(tokens.length)];
        if (VERBOSE) {
          System.out.println("\nTEST: iter content=" + searchToken);
        }
        FacetsCollector fc = new FacetsCollector();
        FacetsCollector.search(searcher, new TermQuery(new Term("content", searchToken)), 10, fc);
        Facets facets;
        if (exec != null) {
          facets = new ConcurrentSortedSetDocValuesFacetCounts(state, fc, exec);
        } else {
          facets = new SortedSetDocValuesFacetCounts(state, fc);
        }

        // Slow, yet hopefully bug-free, faceting:
        @SuppressWarnings({"rawtypes", "unchecked"})
        Map<String, Integer>[] expectedCounts = new HashMap[numDims];
        for (int i = 0; i < numDims; i++) {
          expectedCounts[i] = new HashMap<>();
        }

        for (TestDoc doc : testDocs) {
          if (doc.content.equals(searchToken)) {
            for (int j = 0; j < numDims; j++) {
              if (doc.dims[j] != null) {
                Integer v = expectedCounts[j].get(doc.dims[j]);
                if (v == null) {
                  expectedCounts[j].put(doc.dims[j], 1);
                } else {
                  expectedCounts[j].put(doc.dims[j], v.intValue() + 1);
                }
              }
            }
          }
        }

        List<FacetResult> expected = new ArrayList<>();
        for (int i = 0; i < numDims; i++) {
          List<LabelAndValue> labelValues = new ArrayList<>();
          int totCount = 0;
          for (Map.Entry<String, Integer> ent : expectedCounts[i].entrySet()) {
            labelValues.add(new LabelAndValue(ent.getKey(), ent.getValue()));
            totCount += ent.getValue();
          }
          sortLabelValues(labelValues);
          if (totCount > 0) {
            expected.add(
                new FacetResult(
                    "dim" + i,
                    new String[0],
                    totCount,
                    labelValues.toArray(new LabelAndValue[labelValues.size()]),
                    labelValues.size()));
          }
        }

        // Sort by highest value, tie break by value:
        sortFacetResults(expected);

        List<FacetResult> actual = facets.getAllDims(10);

        // Messy: fixup ties
        // sortTies(actual);

        assertEquals(expected, actual);
      }

      if (exec != null) {
        exec.shutdownNow();
      }
      w.close();
      IOUtils.close(searcher.getIndexReader(), indexDir, taxoDir);
    }
  }

  public void testNonExistentDimension() throws Exception {
    Directory dir = newDirectory();
    FacetsConfig config = new FacetsConfig();
    RandomIndexWriter writer = new RandomIndexWriter(random(), dir);

    Document doc = new Document();
    doc.add(new SortedSetDocValuesFacetField("foo", "bar"));
    writer.addDocument(config.build(doc));
    writer.commit();

    IndexSearcher searcher = newSearcher(writer.getReader());
    SortedSetDocValuesReaderState state =
        new DefaultSortedSetDocValuesReaderState(searcher.getIndexReader());

    ExecutorService exec = randomExecutorServiceOrNull();
    Facets facets = getAllFacets(searcher, state, exec);
    FacetResult result = facets.getTopChildren(5, "non-existent dimension");

    // make sure the result is null (and no exception was thrown)
    assertNull(result);

    writer.close();
    IOUtils.close(searcher.getIndexReader(), dir);
    if (exec != null) {
      exec.shutdownNow();
    }
  }

  private static Facets getAllFacets(
      IndexSearcher searcher, SortedSetDocValuesReaderState state, ExecutorService exec)
      throws IOException, InterruptedException {
    if (random().nextBoolean()) {
      FacetsCollector c = new FacetsCollector();
      searcher.search(new MatchAllDocsQuery(), c);
      if (exec != null) {
        return new ConcurrentSortedSetDocValuesFacetCounts(state, c, exec);
      } else {
        return new SortedSetDocValuesFacetCounts(state, c);
      }
    } else if (exec != null) {
      return new ConcurrentSortedSetDocValuesFacetCounts(state, exec);
    } else {
      return new SortedSetDocValuesFacetCounts(state);
    }
  }

  private ExecutorService randomExecutorServiceOrNull() {
    if (random().nextBoolean()) {
      return null;
    } else {
      return new ThreadPoolExecutor(
          1,
          TestUtil.nextInt(random(), 2, 6),
          Long.MAX_VALUE,
          TimeUnit.MILLISECONDS,
          new LinkedBlockingQueue<Runnable>(),
          new NamedThreadFactory("TestIndexSearcher"));
    }
  }
}
