/*
 * This file is part of LuckPerms, licensed under the MIT License.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package me.lucko.luckperms.sponge.service.calculated;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import me.lucko.luckperms.api.Tristate;
import me.lucko.luckperms.api.context.ContextSet;
import me.lucko.luckperms.api.context.ImmutableContextSet;
import me.lucko.luckperms.common.contexts.ContextSetComparator;
import me.lucko.luckperms.common.model.NodeMapType;
import me.lucko.luckperms.sponge.service.model.LPPermissionService;
import me.lucko.luckperms.sponge.service.model.LPSubject;
import me.lucko.luckperms.sponge.service.model.LPSubjectData;
import me.lucko.luckperms.sponge.service.model.LPSubjectReference;
import me.lucko.luckperms.sponge.service.proxy.ProxyFactory;

import org.spongepowered.api.service.permission.SubjectData;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of {@link LPSubjectData}.
 */
public class CalculatedSubjectData implements LPSubjectData {
    private final LPSubject parentSubject;
    private final NodeMapType type;
    private final LPPermissionService service;

    private final Map<ImmutableContextSet, Map<String, Boolean>> permissions = new ConcurrentHashMap<>();
    private final Map<ImmutableContextSet, Set<LPSubjectReference>> parents = new ConcurrentHashMap<>();
    private final Map<ImmutableContextSet, Map<String, String>> options = new ConcurrentHashMap<>();

    public CalculatedSubjectData(LPSubject parentSubject, NodeMapType type, LPPermissionService service) {
        this.parentSubject = parentSubject;
        this.type = type;
        this.service = service;
    }

    @Override
    public SubjectData sponge() {
        return ProxyFactory.toSponge(this);
    }

    @Override
    public LPSubject getParentSubject() {
        return this.parentSubject;
    }

    @Override
    public NodeMapType getType() {
        return this.type;
    }

    public void replacePermissions(Map<ImmutableContextSet, Map<String, Boolean>> map) {
        this.permissions.clear();
        for (Map.Entry<ImmutableContextSet, Map<String, Boolean>> e : map.entrySet()) {
            this.permissions.put(e.getKey(), new ConcurrentHashMap<>(e.getValue()));
        }
        this.service.invalidateAllCaches();
    }

    public void replaceParents(Map<ImmutableContextSet, List<LPSubjectReference>> map) {
        this.parents.clear();
        for (Map.Entry<ImmutableContextSet, List<LPSubjectReference>> e : map.entrySet()) {
            Set<LPSubjectReference> set = ConcurrentHashMap.newKeySet();
            set.addAll(e.getValue());
            this.parents.put(e.getKey(), set);
        }
        this.service.invalidateAllCaches();
    }

    public void replaceOptions(Map<ImmutableContextSet, Map<String, String>> map) {
        this.options.clear();
        for (Map.Entry<ImmutableContextSet, Map<String, String>> e : map.entrySet()) {
            this.options.put(e.getKey(), new ConcurrentHashMap<>(e.getValue()));
        }
        this.service.invalidateAllCaches();
    }

    @Override
    public ImmutableMap<ImmutableContextSet, ImmutableMap<String, Boolean>> getAllPermissions() {
        ImmutableMap.Builder<ImmutableContextSet, ImmutableMap<String, Boolean>> map = ImmutableMap.builder();
        for (Map.Entry<ImmutableContextSet, Map<String, Boolean>> e : this.permissions.entrySet()) {
            map.put(e.getKey(), ImmutableMap.copyOf(e.getValue()));
        }
        return map.build();
    }

    public Map<String, Boolean> resolvePermissions(ContextSet filter) {
        // get relevant entries
        SortedMap<ImmutableContextSet, Map<String, Boolean>> sorted = new TreeMap<>(ContextSetComparator.reverse());
        for (Map.Entry<ImmutableContextSet, Map<String, Boolean>> entry : this.permissions.entrySet()) {
            if (!entry.getKey().isSatisfiedBy(filter)) {
                continue;
            }

            sorted.put(entry.getKey(), entry.getValue());
        }

        // flatten
        Map<String, Boolean> result = new HashMap<>();
        for (Map<String, Boolean> map : sorted.values()) {
            for (Map.Entry<String, Boolean> e : map.entrySet()) {
                result.putIfAbsent(e.getKey(), e.getValue());
            }
        }

        return result;
    }

    public Map<String, Boolean> resolvePermissions() {
        // get relevant entries
        SortedMap<ImmutableContextSet, Map<String, Boolean>> sorted = new TreeMap<>(ContextSetComparator.reverse());
        for (Map.Entry<ImmutableContextSet, Map<String, Boolean>> entry : this.permissions.entrySet()) {
            sorted.put(entry.getKey(), entry.getValue());
        }

        // flatten
        Map<String, Boolean> result = new HashMap<>();
        for (Map<String, Boolean> map : sorted.values()) {
            for (Map.Entry<String, Boolean> e : map.entrySet()) {
                result.putIfAbsent(e.getKey(), e.getValue());
            }
        }

        return result;
    }

    @Override
    public CompletableFuture<Boolean> setPermission(ImmutableContextSet contexts, String permission, Tristate value) {
        boolean b;
        if (value == Tristate.UNDEFINED) {
            Map<String, Boolean> perms = this.permissions.get(contexts);
            b = perms != null && perms.remove(permission.toLowerCase()) != null;
        } else {
            Map<String, Boolean> perms = this.permissions.computeIfAbsent(contexts, c -> new ConcurrentHashMap<>());
            b = !Objects.equals(perms.put(permission.toLowerCase(), value.asBoolean()), value.asBoolean());
        }
        if (b) {
            this.service.invalidateAllCaches();
        }
        return CompletableFuture.completedFuture(b);
    }

    @Override
    public CompletableFuture<Boolean> clearPermissions() {
        if (this.permissions.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        } else {
            this.permissions.clear();
            this.service.invalidateAllCaches();
            return CompletableFuture.completedFuture(true);
        }
    }

    @Override
    public CompletableFuture<Boolean> clearPermissions(ImmutableContextSet contexts) {
        Map<String, Boolean> perms = this.permissions.get(contexts);
        if (perms == null) {
            return CompletableFuture.completedFuture(false);
        }

        this.permissions.remove(contexts);
        if (!perms.isEmpty()) {
            this.service.invalidateAllCaches();
            return CompletableFuture.completedFuture(true);
        }
        return CompletableFuture.completedFuture(false);
    }

    @Override
    public ImmutableMap<ImmutableContextSet, ImmutableList<LPSubjectReference>> getAllParents() {
        ImmutableMap.Builder<ImmutableContextSet, ImmutableList<LPSubjectReference>> map = ImmutableMap.builder();
        for (Map.Entry<ImmutableContextSet, Set<LPSubjectReference>> e : this.parents.entrySet()) {
            map.put(e.getKey(), ImmutableList.copyOf(e.getValue()));
        }
        return map.build();
    }

    public Set<LPSubjectReference> resolveParents(ContextSet filter) {
        // get relevant entries
        SortedMap<ImmutableContextSet, Set<LPSubjectReference>> sorted = new TreeMap<>(ContextSetComparator.reverse());
        for (Map.Entry<ImmutableContextSet, Set<LPSubjectReference>> entry : this.parents.entrySet()) {
            if (!entry.getKey().isSatisfiedBy(filter)) {
                continue;
            }

            sorted.put(entry.getKey(), entry.getValue());
        }

        // flatten
        Set<LPSubjectReference> result = new LinkedHashSet<>();
        for (Set<LPSubjectReference> set : sorted.values()) {
            for (LPSubjectReference e : set) {
                if (!result.contains(e)) {
                    result.add(e);
                }
            }
        }
        return result;
    }

    public Set<LPSubjectReference> resolveParents() {
        // get relevant entries
        SortedMap<ImmutableContextSet, Set<LPSubjectReference>> sorted = new TreeMap<>(ContextSetComparator.reverse());
        for (Map.Entry<ImmutableContextSet, Set<LPSubjectReference>> entry : this.parents.entrySet()) {
            sorted.put(entry.getKey(), entry.getValue());
        }

        // flatten
        Set<LPSubjectReference> result = new LinkedHashSet<>();
        for (Set<LPSubjectReference> set : sorted.values()) {
            for (LPSubjectReference e : set) {
                if (!result.contains(e)) {
                    result.add(e);
                }
            }
        }
        return result;
    }

    @Override
    public CompletableFuture<Boolean> addParent(ImmutableContextSet contexts, LPSubjectReference parent) {
        Set<LPSubjectReference> set = this.parents.computeIfAbsent(contexts, c -> ConcurrentHashMap.newKeySet());
        boolean b = set.add(parent);
        if (b) {
            this.service.invalidateAllCaches();
        }
        return CompletableFuture.completedFuture(b);
    }

    @Override
    public CompletableFuture<Boolean> removeParent(ImmutableContextSet contexts, LPSubjectReference parent) {
        Set<LPSubjectReference> set = this.parents.get(contexts);
        boolean b = set != null && set.remove(parent);
        if (b) {
            this.service.invalidateAllCaches();
        }
        return CompletableFuture.completedFuture(b);
    }

    @Override
    public CompletableFuture<Boolean> clearParents() {
        if (this.parents.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        } else {
            this.parents.clear();
            this.service.invalidateAllCaches();
            return CompletableFuture.completedFuture(true);
        }
    }

    @Override
    public CompletableFuture<Boolean> clearParents(ImmutableContextSet contexts) {
        Set<LPSubjectReference> set = this.parents.get(contexts);
        if (set == null) {
            return CompletableFuture.completedFuture(false);
        }

        this.parents.remove(contexts);
        this.service.invalidateAllCaches();
        return CompletableFuture.completedFuture(!set.isEmpty());
    }

    @Override
    public ImmutableMap<ImmutableContextSet, ImmutableMap<String, String>> getAllOptions() {
        ImmutableMap.Builder<ImmutableContextSet, ImmutableMap<String, String>> map = ImmutableMap.builder();
        for (Map.Entry<ImmutableContextSet, Map<String, String>> e : this.options.entrySet()) {
            map.put(e.getKey(), ImmutableMap.copyOf(e.getValue()));
        }
        return map.build();
    }

    public Map<String, String> resolveOptions(ContextSet filter) {
        // get relevant entries
        SortedMap<ImmutableContextSet, Map<String, String>> sorted = new TreeMap<>(ContextSetComparator.reverse());
        for (Map.Entry<ImmutableContextSet, Map<String, String>> entry : this.options.entrySet()) {
            if (!entry.getKey().isSatisfiedBy(filter)) {
                continue;
            }

            sorted.put(entry.getKey(), entry.getValue());
        }

        // flatten
        Map<String, String> result = new HashMap<>();
        for (Map<String, String> map : sorted.values()) {
            for (Map.Entry<String, String> e : map.entrySet()) {
                result.putIfAbsent(e.getKey(), e.getValue());
            }
        }

        return result;
    }

    public Map<String, String> resolveOptions() {
        // get relevant entries
        SortedMap<ImmutableContextSet, Map<String, String>> sorted = new TreeMap<>(ContextSetComparator.reverse());
        for (Map.Entry<ImmutableContextSet, Map<String, String>> entry : this.options.entrySet()) {
            sorted.put(entry.getKey(), entry.getValue());
        }

        // flatten
        Map<String, String> result = new HashMap<>();
        for (Map<String, String> map : sorted.values()) {
            for (Map.Entry<String, String> e : map.entrySet()) {
                result.putIfAbsent(e.getKey(), e.getValue());
            }
        }

        return result;
    }

    @Override
    public CompletableFuture<Boolean> setOption(ImmutableContextSet contexts, String key, String value) {
        Map<String, String> options = this.options.computeIfAbsent(contexts, c -> new ConcurrentHashMap<>());
        boolean b = !stringEquals(options.put(key.toLowerCase(), value), value);
        if (b) {
            this.service.invalidateAllCaches();
        }
        return CompletableFuture.completedFuture(b);
    }

    @Override
    public CompletableFuture<Boolean> unsetOption(ImmutableContextSet contexts, String key) {
        Map<String, String> options = this.options.get(contexts);
        boolean b = options != null && options.remove(key.toLowerCase()) != null;
        if (b) {
            this.service.invalidateAllCaches();
        }
        return CompletableFuture.completedFuture(b);
    }

    @Override
    public CompletableFuture<Boolean> clearOptions() {
        if (this.options.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        } else {
            this.options.clear();
            this.service.invalidateAllCaches();
            return CompletableFuture.completedFuture(true);
        }
    }

    @Override
    public CompletableFuture<Boolean> clearOptions(ImmutableContextSet contexts) {
        Map<String, String> map = this.options.get(contexts);
        if (map == null) {
            return CompletableFuture.completedFuture(false);
        }

        this.options.remove(contexts);
        this.service.invalidateAllCaches();
        return CompletableFuture.completedFuture(!map.isEmpty());
    }

    private static boolean stringEquals(String a, String b) {
        return a == null && b == null || a != null && b != null && a.equalsIgnoreCase(b);
    }
}
