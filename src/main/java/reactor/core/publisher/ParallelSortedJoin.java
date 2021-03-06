/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.core.publisher;

import java.util.*;
import java.util.concurrent.atomic.*;

import org.reactivestreams.*;

import reactor.core.Exceptions;

/**
 * Given sorted rail sequences (according to the provided comparator) as List
 * emit the smallest item from these parallel Lists to the Subscriber.
 * <p>
 * It expects the source to emit exactly one list (which could be empty).
 * 
 * @param <T> the value type
 */
final class ParallelSortedJoin<T> extends Flux<T> {

	final ParallelFlux<List<T>> source;
	
	final Comparator<? super T> comparator;

	public ParallelSortedJoin(ParallelFlux<List<T>> source, Comparator<? super T> comparator) {
		this.source = source;
		this.comparator = comparator;
	}

	@Override
	public long getPrefetch() {
		return Long.MAX_VALUE;
	}

	@Override
	public void subscribe(Subscriber<? super T> s) {
		SortedJoinSubscription<T> parent = new SortedJoinSubscription<>(s, source.parallelism(), comparator);
		s.onSubscribe(parent);
		
		source.subscribe(parent.subscribers);
	}
	
	static final class SortedJoinSubscription<T> implements Subscription {
		final Subscriber<? super T> actual;
		
		final SortedJoinInnerSubscriber<T>[] subscribers;
		
		final List<T>[] lists;
		
		final int[] indexes;
		
		final Comparator<? super T> comparator;
		
		volatile int wip;
		@SuppressWarnings("rawtypes")
		static final AtomicIntegerFieldUpdater<SortedJoinSubscription> WIP =
				AtomicIntegerFieldUpdater.newUpdater(SortedJoinSubscription.class, "wip");
		
		volatile long requested;
		@SuppressWarnings("rawtypes")
		static final AtomicLongFieldUpdater<SortedJoinSubscription> REQUESTED =
				AtomicLongFieldUpdater.newUpdater(SortedJoinSubscription.class, "requested");
		
		volatile boolean cancelled;

		volatile int remaining;
		@SuppressWarnings("rawtypes")
		static final AtomicIntegerFieldUpdater<SortedJoinSubscription> REMAINING =
				AtomicIntegerFieldUpdater.newUpdater(SortedJoinSubscription.class, "remaining");

		volatile Throwable error;
		@SuppressWarnings("rawtypes")
		static final AtomicReferenceFieldUpdater<SortedJoinSubscription, Throwable> ERROR =
				AtomicReferenceFieldUpdater.newUpdater(SortedJoinSubscription.class, Throwable.class, "error");

		@SuppressWarnings("unchecked")
		public SortedJoinSubscription(Subscriber<? super T> actual, int n, Comparator<? super T> comparator) {
			this.actual = actual;
			this.comparator = comparator;
			
			SortedJoinInnerSubscriber<T>[] s = new SortedJoinInnerSubscriber[n];
			
			for (int i = 0; i < n; i++) {
				s[i] = new SortedJoinInnerSubscriber<>(this, i);
			}
			this.subscribers = s;
			this.lists = new List[n];
			this.indexes = new int[n];
			REMAINING.lazySet(this, n);
		}
		
		@Override
		public void request(long n) {
			if (Operators.validate(n)) {
				Operators.getAndAddCap(REQUESTED, this, n);
				if (remaining == 0) {
					drain();
				}
			}
		}

		@Override
		public void cancel() {
			if (!cancelled) {
				cancelled = true;
				cancelAll();
				if (WIP.getAndIncrement(this) == 0) {
					Arrays.fill(lists, null);
				}
			}
		}
		
		void cancelAll() {
			for (SortedJoinInnerSubscriber<T> s : subscribers) {
				s.cancel();
			}
		}
		
		void innerNext(List<T> value, int index) {
			lists[index] = value;
			if (REMAINING.decrementAndGet(this) == 0) {
				drain();
			}
		}
		
		void innerError(Throwable e) {
			if (Exceptions.addThrowable(ERROR, this, e)) {
				drain();
			} else {
				Operators.onErrorDropped(e);
			}
		}
		
		void drain() {
			if (WIP.getAndIncrement(this) != 0) {
				return;
			}
			
			int missed = 1;
			Subscriber<? super T> a = actual;
			List<T>[] lists = this.lists;
			int[] indexes = this.indexes;
			int n = indexes.length;
			
			for (;;) {
				
				long r = requested;
				long e = 0L;
				
				while (e != r) {
					if (cancelled) {
						Arrays.fill(lists, null);
						return;
					}
					
					Throwable ex = error;
					if (ex != null) {
						ex = Exceptions.terminate(ERROR, this);
						cancelAll();
						Arrays.fill(lists, null);
						a.onError(ex);
						return;
					}
					
					T min = null;
					int minIndex = -1;

					for (int i = 0; i < n; i++) {
						List<T> list = lists[i];
						int index = indexes[i];
						
						if (list.size() != index) {
							if (min == null) {
								min = list.get(index);
								minIndex = i;
							} else {
								T b = list.get(index);
								if (comparator.compare(min, b) > 0) {
									min = b;
									minIndex = i;
								}
							}
						}
					}
					
					if (min == null) {
						Arrays.fill(lists, null);
						a.onComplete();
						return;
					}
					
					a.onNext(min);
					
					indexes[minIndex]++;
					
					e++;
				}
				
				if (e == r) {
					if (cancelled) {
						Arrays.fill(lists, null);
						return;
					}
					
					Throwable ex = error;
					if (ex != null) {
						ex = Exceptions.terminate(ERROR, this);
						cancelAll();
						Arrays.fill(lists, null);
						a.onError(ex);
						return;
					}

					boolean empty = true;
					
					for (int i = 0; i < n; i++) {
						if (indexes[i] != lists[i].size()) {
							empty = false;
							break;
						}
					}
					
					if (empty) {
						Arrays.fill(lists, null);
						a.onComplete();
						return;
					}
				}
				
				if (e != 0 && r != Long.MAX_VALUE) {
					REQUESTED.addAndGet(this, -e);
				}
				
				int w = wip;
				if (w == missed) {
					missed = WIP.addAndGet(this, -missed);
					if (missed == 0) {
						break;
					}
				} else {
					missed = w;
				}
			}
		}
	}
	
	static final class SortedJoinInnerSubscriber<T> implements Subscriber<List<T>> {

		final SortedJoinSubscription<T> parent;
		
		final int index;
		
		volatile Subscription s;
		@SuppressWarnings("rawtypes")
		static final AtomicReferenceFieldUpdater<SortedJoinInnerSubscriber, Subscription> S =
				AtomicReferenceFieldUpdater.newUpdater(SortedJoinInnerSubscriber.class, Subscription.class, "s");
		
		public SortedJoinInnerSubscriber(SortedJoinSubscription<T> parent, int index) {
			this.parent = parent;
			this.index = index;
		}
		
		@Override
		public void onSubscribe(Subscription s) {
			if (Operators.setOnce(S, this, s)) {
				s.request(Long.MAX_VALUE);
			}
		}

		@Override
		public void onNext(List<T> t) {
			parent.innerNext(t, index);
		}

		@Override
		public void onError(Throwable t) {
			parent.innerError(t);
		}

		@Override
		public void onComplete() {
			// ignored
		}
		
		void cancel() {
			Operators.terminate(S, this);
		}
	}
}
