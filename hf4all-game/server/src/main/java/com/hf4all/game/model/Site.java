package com.hf4all.game.model;

import java.util.ArrayList;
import java.util.List;

/** Ownership state of a Site ({@code G.sites[id]}). */
public final class Site {
    public Integer claim;
    public boolean factory;
    public boolean colony;
    public boolean busted;
    /** Players whose Humans have landed here (first landing may take glory). */
    public List<Integer> visited = new ArrayList<>();
}
