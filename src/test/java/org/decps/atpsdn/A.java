package org.decps.atpsdn;

public class A
{ public void p() { System.out.println("A.p");}
    public void q() { System.out.println("A.q");}
    public void r() { p(); q();}
}