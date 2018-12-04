/*
 * Copyright 2009 the original author or authors.
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
package org.gradle.api.internal;

import com.google.common.collect.Lists;
import groovy.lang.Closure;
import org.gradle.api.Action;
import org.gradle.api.DomainObjectCollection;
import org.gradle.api.DomainObjectProvider;
import org.gradle.api.artifacts.ConfigurablePublishArtifact;
import org.gradle.api.internal.collections.CollectionEventRegister;
import org.gradle.api.internal.collections.CollectionFilter;
import org.gradle.api.internal.collections.DefaultCollectionEventRegister;
import org.gradle.api.internal.collections.ElementSource;
import org.gradle.api.internal.collections.FilteredCollection;
import org.gradle.api.internal.provider.AbstractReadOnlyProvider;
import org.gradle.api.internal.provider.CollectionProviderInternal;
import org.gradle.api.internal.provider.ProviderInternal;
import org.gradle.api.internal.provider.Providers;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Provider;
import org.gradle.api.specs.Spec;
import org.gradle.api.specs.Specs;
import org.gradle.internal.Cast;
import org.gradle.internal.ImmutableActionSet;
import org.gradle.util.ConfigureUtil;

import javax.annotation.Nullable;
import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

public class DefaultDomainObjectCollection<T> extends AbstractCollection<T> implements DomainObjectCollection<T>, WithEstimatedSize, WithMutationGuard {

    private final Class<? extends T> type;
    private final CollectionEventRegister<T> eventRegister;
    private final ElementSource<T> store;

    protected DefaultDomainObjectCollection(Class<? extends T> type, ElementSource<T> store, CollectionCallbackActionDecorator callbackActionDecorator) {
        this(type, store, new DefaultCollectionEventRegister<T>(type, callbackActionDecorator));
    }

    protected DefaultDomainObjectCollection(Class<? extends T> type, ElementSource<T> store, final CollectionEventRegister<T> eventRegister) {
        this.type = type;
        this.store = store;
        this.eventRegister = eventRegister;
        this.store.onRealize(new Action<T>() {
            @Override
            public void execute(T value) {
                doAddRealized(value, eventRegister.getAddActions());
            }
        });
    }

    protected DefaultDomainObjectCollection(DefaultDomainObjectCollection<? super T> collection, CollectionFilter<T> filter) {
        this(filter.getType(), collection.filteredStore(filter), collection.filteredEvents(filter));
    }

    protected void realized(ProviderInternal<? extends T> provider) {
        getStore().realizeExternal(provider);
    }

    public Class<? extends T> getType() {
        return type;
    }

    protected String getTypeDisplayName() {
        return getType().getSimpleName();
    }

    protected ElementSource<T> getStore() {
        return store;
    }

    protected CollectionEventRegister<T> getEventRegister() {
        return eventRegister;
    }

    protected CollectionFilter<T> createFilter(Spec<? super T> filter) {
        return createFilter(getType(), filter);
    }

    protected <S extends T> CollectionFilter<S> createFilter(Class<S> type) {
        return new CollectionFilter<S>(type);
    }

    protected <S extends T> CollectionFilter<S> createFilter(Class<? extends S> type, Spec<? super S> spec) {
        return new CollectionFilter<S>(type, spec);
    }

    protected <S extends T> DefaultDomainObjectCollection<S> filtered(CollectionFilter<S> filter) {
        return new DefaultDomainObjectCollection<S>(this, filter);
    }

    protected <S extends T> ElementSource<S> filteredStore(final CollectionFilter<S> filter) {
        return filteredStore(filter, store);
    }

    protected <S extends T> ElementSource<S> filteredStore(CollectionFilter<S> filter, ElementSource<T> elementSource) {
        return new FilteredCollection<T, S>(elementSource, filter);
    }

    protected <S extends T> CollectionEventRegister<S> filteredEvents(CollectionFilter<S> filter) {
        return eventRegister.filtered(filter);
    }

    public DomainObjectCollection<T> matching(final Spec<? super T> spec) {
        return filtered(createFilter(spec));
    }

    public DomainObjectCollection<T> matching(Closure spec) {
        return matching(Specs.<T>convertClosureToSpec(spec));
    }

    public <S extends T> DomainObjectCollection<S> withType(final Class<S> type) {
        return filtered(createFilter(type));
    }

    public Iterator<T> iterator() {
        return new IteratorImpl(store.iterator());
    }

    Iterator<T> iteratorNoFlush() {
        if (store.constantTimeIsEmpty()) {
            return Collections.emptyIterator();
        }

        return new IteratorImpl(store.iteratorNoFlush());
    }

    public void all(Action<? super T> action) {
        assertMutable("all(Action)");
        Action<? super T> decoratedAction = addEagerAction(action);

        if (store.constantTimeIsEmpty()) {
            return;
        }

        // copy in case any actions mutate the store
        // linked list because the underlying store may preserve order
        // We make best effort not to create an intermediate collection if this container
        // is empty.
        Collection<T> copied = null;
        for (T t : this) {
            if (copied == null) {
                copied = Lists.newArrayListWithExpectedSize(estimatedSize());
            }
            copied.add(t);
        }
        if (copied != null) {
            for (T t : copied) {
                decoratedAction.execute(t);
            }
        }
    }

    @Override
    public void configureEach(Action<? super T> action) {
        assertMutable("configureEach(Action)");
        Action<? super T> wrappedAction = withMutationDisabled(action);
        Action<? super T> registerLazyAddActionDecorated = eventRegister.registerLazyAddAction(wrappedAction);

        // copy in case any actions mutate the store
        Collection<T> copied = null;
        Iterator<T> iterator = iteratorNoFlush();
        while (iterator.hasNext()) {
            // only create an intermediate collection if there's something to copy
            if (copied == null) {
                copied = Lists.newArrayListWithExpectedSize(estimatedSize());
            }
            copied.add(iterator.next());
        }

        if (copied != null) {
            for (T next : copied) {
                registerLazyAddActionDecorated.execute(next);
            }
        }
    }

    protected <I extends T> Action<? super I> withMutationDisabled(Action<? super I> action) {
        return getMutationGuard().withMutationDisabled(action);
    }

    public void all(Closure action) {
        all(toAction(action));
    }

    public <S extends T> DomainObjectCollection<S> withType(Class<S> type, Action<? super S> configureAction) {
        assertMutable("withType(Class, Action)");
        DomainObjectCollection<S> result = withType(type);
        result.all(configureAction);
        return result;
    }

    public <S extends T> DomainObjectCollection<S> withType(Class<S> type, Closure configureClosure) {
        return withType(type, toAction(configureClosure));
    }

    public Action<? super T> whenObjectAdded(Action<? super T> action) {
        assertMutable("whenObjectAdded(Action)");
        return addEagerAction(action);
    }

    public void whenObjectAdded(Closure action) {
        whenObjectAdded(toAction(action));
    }

    private Action<? super T> addEagerAction(Action<? super T> action) {
        store.realizePending(type);
        return eventRegister.registerEagerAddAction(type, action);
    }

    public Action<? super T> whenObjectRemoved(Action<? super T> action) {
        eventRegister.registerRemoveAction(type, action);
        return action;
    }

    public void whenObjectRemoved(Closure action) {
        whenObjectRemoved(toAction(action));
    }

    private Action<? super T> toAction(Closure action) {
        return ConfigureUtil.configureUsing(action);
    }

    public boolean add(T toAdd) {
        assertMutable("add(T)");
        assertMutableCollectionContents();
        return doAdd(toAdd, eventRegister.getAddActions());
    }

    protected <I extends T> boolean add(I toAdd, Action<? super I> notification) {
        assertMutableCollectionContents();
        return doAdd(toAdd, notification);
    }

    protected <I extends T> boolean doAdd(I toAdd, Action<? super I> notification) {
        if (getStore().add(toAdd)) {
            didAdd(toAdd);
            notification.execute(toAdd);
            return true;
        } else {
            return false;
        }
    }

    private <I extends T> boolean doAddRealized(I toAdd, Action<? super I> notification) {
        if (getStore().addRealized(toAdd)) {
            didAdd(toAdd);
            notification.execute(toAdd);
            return true;
        } else {
            return false;
        }
    }

    @Override
    public void addLater(Provider<? extends T> provider) {
        assertMutable("addLater(Provider)");
        assertMutableCollectionContents();

        DomainObjectCreatingProvider<T> providerInternal = objectFactory.newInstance(DomainObjectCreatingProvider.class, this, getType(), provider);
        store.addPending(providerInternal);

        if (eventRegister.isSubscribed(providerInternal.getType())) {
            doAddRealized(provider.get(), eventRegister.getAddActions());
        }
    }

    @Override
    public void addAllLater(Provider<? extends Iterable<T>> provider) {
        assertMutable("addAllLater(Provider)");
        assertMutableCollectionContents();
        CollectionProviderInternal<T, ? extends Iterable<T>> providerInternal = Cast.uncheckedCast(provider);
        store.addPendingCollection(providerInternal);
        if (eventRegister.isSubscribed(providerInternal.getElementType())) {
            for (T value : provider.get()) {
                doAddRealized(value, eventRegister.getAddActions());
            }
        }
    }

    protected void didAdd(T toAdd) {
    }

    public boolean addAll(Collection<? extends T> c) {
        assertMutable("addAll(Collection<T>)");
        assertMutableCollectionContents();
        boolean changed = false;
        for (T o : c) {
            if (doAdd(o, eventRegister.getAddActions())) {
                changed = true;
            }
        }
        return changed;
    }

    public void clear() {
        assertMutable("clear()");
        assertMutableCollectionContents();
        if (store.constantTimeIsEmpty()) {
            return;
        }
        List<T> c = Lists.newArrayList(store.iteratorNoFlush());
        getStore().clear();
        for (T o : c) {
            eventRegister.fireObjectRemoved(o);
        }
    }

    public boolean contains(Object o) {
        return getStore().contains(o);
    }

    public boolean containsAll(Collection<?> c) {
        return getStore().containsAll(c);
    }

    public boolean isEmpty() {
        return getStore().isEmpty();
    }

    public boolean remove(Object o) {
        assertMutable("remove(Object)");
        assertMutableCollectionContents();
        return doRemove(o);
    }

    private boolean doRemove(Object o) {
        if (o instanceof ProviderInternal) {
            ProviderInternal<? extends T> providerInternal = Cast.uncheckedCast(o);
            if (getStore().removePending(providerInternal)) {
                // NOTE: When removing provider, we don't need to fireObjectRemoved as they were never added in the first place.
                didRemove(providerInternal);
                return true;
            } else if (getType().isAssignableFrom(providerInternal.getType()) && providerInternal.isPresent()) {
                // The provider is of compatible type and the element was either already realized or we are removing a provider to the element
                o = providerInternal.get();
            }
            // Else, the provider is of incompatible type, maybe we have a domain object collection of Provider, fallthrough
        }

        if (getStore().remove(o)) {
            @SuppressWarnings("unchecked") T cast = (T) o;
            didRemove(cast);
            eventRegister.fireObjectRemoved(cast);
            return true;
        } else {
            return false;
        }
    }

    protected void didRemove(T t) {
    }

    protected void didRemove(ProviderInternal<? extends T> t) {
    }

    public boolean removeAll(Collection<?> c) {
        assertMutable("removeAll(Collection)");
        assertMutableCollectionContents();
        if (store.constantTimeIsEmpty()) {
            return false;
        }
        boolean changed = false;
        for (Object o : c) {
            if (doRemove(o)) {
                changed = true;
            }
        }
        return changed;
    }

    public boolean retainAll(Collection<?> target) {
        assertMutable("retainAll(Collection)");
        assertMutableCollectionContents();
        Object[] existingItems = toArray();
        boolean changed = false;
        for (Object existingItem : existingItems) {
            if (!target.contains(existingItem)) {
                doRemove(existingItem);
                changed = true;
            }
        }
        return changed;
    }

    public int size() {
        return store.size();
    }

    @Override
    public int estimatedSize() {
        return store.estimatedSize();
    }

    @Override
    public MutationGuard getMutationGuard() {
        return store.getMutationGuard();
    }

    public Collection<T> findAll(Closure cl) {
        return findAll(cl, new ArrayList<T>());
    }

    protected <S extends Collection<? super T>> S findAll(Closure cl, S matches) {
        if (store.constantTimeIsEmpty()) {
            return matches;
        }
        for (T t : filteredStore(createFilter(Specs.<Object>convertClosureToSpec(cl)))) {
            matches.add(t);
        }
        return matches;
    }

    /**
     * Asserts that the container can be modified in any way by the given method.
     */
    protected final void assertMutable(String methodName) {
        getMutationGuard().assertMutationAllowed(methodName, this);
    }

    /**
     * Subclasses may override this method to prevent add/remove methods.
     *
     * @see DefaultDomainObjectSet
     */
    protected void assertMutableCollectionContents() {
        // no special validation
    }

    protected class IteratorImpl implements Iterator<T>, WithEstimatedSize {
        private final Iterator<T> iterator;
        private T currentElement;

        public IteratorImpl(Iterator<T> iterator) {
            this.iterator = iterator;
        }

        public boolean hasNext() {
            return iterator.hasNext();
        }

        public T next() {
            currentElement = iterator.next();
            return currentElement;
        }

        public void remove() {
            assertMutable("iterator().remove()");
            assertMutableCollectionContents();
            iterator.remove();
            didRemove(currentElement);
            getEventRegister().fireObjectRemoved(currentElement);
            currentElement = null;
        }

        @Override
        public int estimatedSize() {
            return DefaultDomainObjectCollection.this.estimatedSize();
        }
    }

    protected abstract class AbstractDomainObjectProvider<I extends T> extends AbstractReadOnlyProvider<I> implements DomainObjectProvider<I> {
        private final Class<I> type;

        protected AbstractDomainObjectProvider(Class<I> type) {
            this.type = type;
        }

        @Nullable
        @Override
        public Class<I> getType() {
            return type;
        }

        @Override
        public boolean isPresent() {
            // TODO: How can we find our object?
            return true;
        }

        @Override
        public String toString() {
            return String.format("provider(%s, %s)", getTypeDisplayName(), getType());
        }
    }

    protected abstract class AbstractDomainObjectCreatingProvider<I extends T> extends AbstractDomainObjectProvider<I> {
        private I object;
        private RuntimeException failure;
        protected ImmutableActionSet<I> onCreate;
        private boolean removedBeforeRealized = false;

        public AbstractDomainObjectCreatingProvider(Class<I> type, @Nullable Action<? super I> configureAction) {
            super(type);
            this.onCreate = ImmutableActionSet.<I>empty().mergeFrom(getEventRegister().getAddActions());

            if (configureAction != null) {
                configure(configureAction);
            }
        }

        @Override
        public void configure(final Action<? super I> action) {
            assertMutable("DomainObjectProvider.configure(Action)");
            Action<? super I> wrappedAction = withMutationDisabled(action);
            if (object != null) {
                // Already realized, just run the action now
                wrappedAction.execute(object);
                return;
            }
            // Collect any container level add actions then add the object specific action
            onCreate = onCreate.mergeFrom(getEventRegister().getAddActions()).add(wrappedAction);
        }

        @Override
        public I get() {
            if (wasElementRemoved()) {
                // TODO: How do we detect this for non-named domain objects?
                throw new IllegalStateException(String.format("The domain object (%s) for this provider is no longer present in its container.", type.getSimpleName()));
            }
            return super.get();
        }

        @Override
        public I getOrNull() {
            if (wasElementRemoved()) {
                return null;
            }
            if (failure != null) {
                throw failure;
            }
            if (object == null) {
                tryCreate();
            }
            return object;
        }

        protected void tryCreate() {
            try {
                // Collect any container level add actions added since the last call to configure()
                onCreate = onCreate.mergeFrom(getEventRegister().getAddActions());

                // Create the domain object
                object = createDomainObject();

                // Register the domain object
                add(object, onCreate);
                realized(AbstractDomainObjectCreatingProvider.this);
                onLazyDomainObjectRealized();
            } catch (Throwable ex) {
                failure = domainObjectCreationException(ex);
                throw failure;
            } finally {
                // Discard state that is no longer required
                onCreate = ImmutableActionSet.empty();
            }
        }

        protected abstract I createDomainObject();

        protected void onLazyDomainObjectRealized() {
            // Do nothing.
        }

        protected boolean wasElementRemoved() {
            // Check for presence as the domain object may have been replaced
            return (wasElementRemovedBeforeRealized() || wasElementRemovedAfterRealized()) && !isPresent();
        }

        private boolean wasElementRemovedBeforeRealized() {
            return removedBeforeRealized;
        }

        private boolean wasElementRemovedAfterRealized() {
            return false;
            // TODO:
            // return object != null && findByNameWithoutRules(getName()) == null;
        }

        protected RuntimeException domainObjectCreationException(Throwable cause) {
            return new IllegalStateException(String.format("Could not create domain object (%s)", getType().getSimpleName()), cause);
        }
    }
    // Cannot be private due to reflective instantiation
    public class DomainObjectCreatingProvider<I extends T> extends AbstractDomainObjectCreatingProvider<I> {
        private final Provider<I> domainObjectProvider;

        public DomainObjectCreatingProvider(Class<I> type, Provider<I> domainObjectProvider) {
            super(type, null);
            this.domainObjectProvider = domainObjectProvider;
        }

        @Override
        protected I createDomainObject() {
            return domainObjectProvider.get();
        }
    }
}
