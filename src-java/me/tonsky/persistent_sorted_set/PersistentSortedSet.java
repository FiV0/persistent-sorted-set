package me.tonsky.persistent_sorted_set;

import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import clojure.lang.*;

@SuppressWarnings("unchecked")
public class PersistentSortedSet<Key, Address> extends APersistentSortedSet<Key, Address> implements IEditableCollection, ITransientSet, Reversible, Sorted, IReduce, IPersistentSortedSet<Key, Address> {

  public static ANode[] EARLY_EXIT = new ANode[0];
  public static ANode[] UNCHANGED  = new ANode[0];

  public static int MIN_LEN = 32, MAX_LEN = 64, EXPAND_LEN = 8;

  public static final PersistentSortedSet EMPTY = new PersistentSortedSet();

  public static void setMaxLen(int maxLen) {
    MAX_LEN = maxLen;
    MIN_LEN = maxLen >>> 1;
  }

  public Address _address;
  public ANode<Key, Address> _root;
  public int _count;
  public int _version;
  public final AtomicBoolean _edit;
  public IStorage<Key, Address> _storage;

  public PersistentSortedSet() {
    this(null, RT.DEFAULT_COMPARATOR);
  }

  public PersistentSortedSet(Comparator<Key> cmp) {
    this(null, cmp);
  }

  public PersistentSortedSet(IPersistentMap meta, Comparator<Key> cmp) {
    this(meta, cmp, null, null, new Leaf<Key, Address>(0, null), 0, null, 0);
  }

  public PersistentSortedSet(IPersistentMap meta, Comparator<Key> cmp, Address address, IStorage<Key, Address> storage, ANode<Key, Address> root, int count, AtomicBoolean edit, int version) {
    super(meta, cmp);
    _address = address;
    _root    = root;
    _count   = count;
    _version = version;
    _edit    = edit;
    _storage = storage;
  }

  public ANode<Key, Address> root() {
    assert _address != null || _root != null;
    return _address != null ? _storage.restore(_address) : _root;
  }

  private int alterCount(int delta) {
    return _count < 0 ? _count : _count + delta;
  }

  public boolean editable() {
    return _edit != null && _edit.get();
  }

  public Address address(Address address) {
    _address = address;
    return address;
  }

  // IPersistentSortedSet
  @Override
  public Seq slice(Key from, Key to) {
    return slice(from, to, _cmp);
  }

  @Override
  public Seq slice(Key from, Key to, Comparator<Key> cmp) {
    assert from == null || to == null || cmp.compare(from, to) <= 0 : "From " + from + " after to " + to;
    Seq seq = null;
    ANode node = root();

    if (node.len() == 0)
      return null;

    if (from == null) {
      while (true) {
        if (node instanceof Branch) {
          seq = new Seq(null, this, seq, node, 0, null, null, true, _version);
          node = seq.child();
        } else {
          seq = new Seq(null, this, seq, node, 0, to, cmp, true, _version);
          return seq.over() ? null : seq;
        }
      }
    }

    while (true) {
      int idx = node.searchFirst(from, cmp);
      if (idx < 0) idx = -idx-1;
      if (idx == node._len) return null;
      if (node instanceof Branch) {
        seq = new Seq(null, this, seq, node, idx, null, null, true, _version);
        node = seq.child();
      } else {
        seq = new Seq(null, this, seq, node, idx, to, cmp, true, _version);
        return seq.over() ? null : seq;
      }
    }
  }

  public Seq rslice(Key from, Key to) {
    return rslice(from, to, _cmp);
  }

  public Seq rslice(Key from, Key to, Comparator<Key> cmp) {
    assert from == null || to == null || cmp.compare(from, to) >= 0 : "From " + from + " before to " + to;
    Seq seq = null;
    ANode node = root();

    if (node.len() == 0)
      return null;

    if (from == null) {
      while (true) {
        int idx = node._len - 1;
        if (node instanceof Branch) {
          seq = new Seq(null, this, seq, node, idx, null, null, false, _version);
          node = seq.child();
        } else {
          seq = new Seq(null, this, seq, node, idx, to, cmp, false, _version);
          return seq.over() ? null : seq;
        }
      }
    }

    while (true) {
      if (node instanceof Branch) {
        int idx = node.searchLast(from, cmp) + 1;
        if (idx == node._len) --idx; // last or beyond, clamp to last
        seq = new Seq(null, this, seq, node, idx, null, null, false, _version);
        node = seq.child();
      } else {
        int idx = node.searchLast(from, cmp);
        if (idx == -1) { // not in this, so definitely in prev
          seq = new Seq(null, this, seq, node, 0, to, cmp, false, _version);
          return seq.advance() ? seq : null;
        } else { // exact match
          seq = new Seq(null, this, seq, node, idx, to, cmp, false, _version);
          return seq.over() ? null : seq;
        }
      }
    }
  }

  public void walkAddresses(IFn onAddress) {
    if (_address != null) {
      onAddress.invoke(_address);
    }
    root().walkAddresses(_storage, onAddress);
  }

  public Address store() {
    assert _storage != null;

    if (_address == null) {
      address(_root.store(_storage));
      _root = null;
    }

    return _address;
  }

  public Address store(IStorage<Key, Address> storage) {
    _storage = storage;
    return store();
  }

  public String toString() {
    StringBuilder sb = new StringBuilder("#{");
    for(Object o: this)
      sb.append(o).append(" ");
    if (sb.charAt(sb.length() - 1) == " ".charAt(0))
      sb.delete(sb.length() - 1, sb.length());
    sb.append("}");
    return sb.toString();
  }

  public String str() {
    return root().str(_storage, 0);
  }

  // IObj
  public PersistentSortedSet withMeta(IPersistentMap meta) {
    if (_meta == meta)
      return this;
    return new PersistentSortedSet(meta, _cmp, _address, _storage, _root, _count, _edit, _version);
  }

  // Counted
  public int count() {
    if (_count < 0)
      _count = root().count(_storage);
    // assert _count == _root.count(_storage) : _count + " != " + _root.count(_storage);
    return _count;
  }

  // Sorted
  public Comparator comparator() {
    return _cmp;
  }

  public Object entryKey(Object entry) {
    return entry;
  }

  // IReduce
  public Object reduce(IFn f) {
    Seq seq = (Seq) seq();
    return seq == null ? f.invoke() : seq.reduce(f);
  }

  public Object reduce(IFn f, Object start) {
    Seq seq = (Seq) seq();
    return seq == null ? start : seq.reduce(f, start);
  }

  // IPersistentCollection
  public PersistentSortedSet empty() {
    return new PersistentSortedSet(_meta, _cmp);
  }

  public PersistentSortedSet cons(Object key) {
    return cons(key, _cmp);
  }

  public PersistentSortedSet cons(Object key, Comparator cmp) {
    ANode[] nodes = root().add(_storage, (Key) key, cmp, _edit);

    if (UNCHANGED == nodes)
      return this;

    if (editable()) {
      if (1 == nodes.length) {
        _address = null;
        _root = nodes[0];
      } else if (2 == nodes.length) {
        Object[] keys = new Object[] { nodes[0].maxKey(), nodes[1].maxKey() };
        _address = null;

        _root = new Branch(nodes[0].level() + 1, 2, keys, null, new Object[] { nodes[0], nodes[1] }, _edit);
      }
      _count = alterCount(1);
      _version += 1;
      return this;
    }

    if (1 == nodes.length)
      return new PersistentSortedSet(_meta, _cmp, null, _storage, nodes[0], alterCount(1), _edit, _version + 1);

    Object[] keys = new Object[] { nodes[0].maxKey(), nodes[1].maxKey() };
    Object[] children = Arrays.copyOf(nodes, nodes.length, new Object[0].getClass());

    ANode newRoot = new Branch(nodes[0].level() + 1, 2, keys, null, children, _edit);
    return new PersistentSortedSet(_meta, _cmp, null, _storage, newRoot, alterCount(1), _edit, _version + 1);
  }

  // IPersistentSet
  public PersistentSortedSet disjoin(Object key) {
    return disjoin(key, _cmp);
  }

  public PersistentSortedSet disjoin(Object key, Comparator cmp) {
    ANode[] nodes = root().remove(_storage, (Key) key, null, null, cmp, _edit);

    // not in set
    if (UNCHANGED == nodes)
      return this;

    // in place update
    if (nodes == EARLY_EXIT) {
      _address = null;
      _count = alterCount(-1);
      _version += 1;
      return this;
    }

    ANode newRoot = nodes[1];
    if (editable()) {
      if (newRoot instanceof Branch && newRoot._len == 1)
        newRoot = ((Branch) newRoot).child(_storage, 0);
      _address = null;
      _root = newRoot;
      _count = alterCount(-1);
      _version += 1;
      return this;
    }
    if (newRoot instanceof Branch && newRoot._len == 1) {
      newRoot = ((Branch) newRoot).child(_storage, 0);
      return new PersistentSortedSet(_meta, _cmp, null, _storage, newRoot, alterCount(-1), _edit, _version + 1);
    }
    return new PersistentSortedSet(_meta, _cmp, null, _storage, newRoot, alterCount(-1), _edit, _version + 1);
  }

  public boolean contains(Object key) {
    return root().contains(_storage, (Key) key, _cmp);
  }

  // IEditableCollection
  public PersistentSortedSet asTransient() {
    if (editable())
      throw new IllegalStateException("Expected persistent set");
    return new PersistentSortedSet(_meta, _cmp, _address, _storage, _root, _count, new AtomicBoolean(true), _version);
  }

  // ITransientCollection
  public PersistentSortedSet conj(Object key) {
    return cons(key, _cmp);
  }

  public PersistentSortedSet persistent() {
    if (!editable())
      throw new IllegalStateException("Expected transient set");
    _edit.set(false);
    return this;
  }

  // Iterable
  public Iterator iterator() {
    return new JavaIter((Seq) seq());
  }
}
