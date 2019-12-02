package com.leecode;
class Solution {
    public static ListNode addTwoNumbers(ListNode l1, ListNode l2) {
        ListNode listNodeHead = null; // 头
        ListNode listNodeTail = null; // 尾

        int flag = 0;
        int temp = -1;
        
        int flag1_2 = 12;
        
        while(true){
        	if( flag1_2 == 12) { 
        		temp = l1.val + l2.val + flag;
        	} else if(flag1_2 == 1){
        		temp =l1.val  + flag;
        	} else if(flag1_2 == 2){
        		temp =  l2.val  + flag;
        	}
        	
        	flag = temp >= 10 ? 1 : 0;
        	temp = temp%10;
        	System.out.println("numtemp = " + temp );
        	
        	if(listNodeHead == null){
            	listNodeHead = new ListNode(temp);
            	listNodeTail = listNodeHead;
        	} else {
        		listNodeTail.next = new ListNode(temp);
        		listNodeTail = listNodeTail.next;
        	}
        	
        	if(l1.next == null && l2.next == null) {
        		if(flag == 1) 
        			listNodeTail.next = new ListNode(flag);
        		break;
        	}
        	
        	if(l1.next != null){
        		l1 = l1.next;
        	}else {
        		flag1_2 = 2;
        	}
        	if (l2.next != null) {
        		l2 = l2.next;
        	}else {
        		flag1_2 = 1;
        	}
        }        
        myPrint(listNodeHead);
        return listNodeHead;
    }
    
    public static ListNode addTwoNumbers2(ListNode l1, ListNode l2) {
    	ListNode listNodeHead;
    	int flag = 0;
    	int l1_len = 1;
    	int l2_len = 1;
    	int tempNum = 0;
    	
    	ListNode temp = null;
    	temp = l1;
    	while(temp.next != null) {
    		l1_len++;
    		temp = temp.next;
    	}
    	
    	temp = l2;
    	while(temp.next != null) {
    		l2_len++;
    		temp = temp.next;
    	}    	
    	
    	if(l1_len >= l2_len){
    		listNodeHead = l1;
    		while(true){
    			tempNum = l1.val + l2.val + flag;
    			l1.val =tempNum%10;
    			System.out.println("listNodeHead = l1 numtemp = " + l1.val );
    			flag = tempNum > 9 ? 1 : 0;     			
    			//l1 = l1.next;
    			if(l2.next == null){
    				if( flag == 1) {
    					if(l1.next ==null) {
    						l1.next = new ListNode(1);
    					} else {
    						l1.next.val = l1.next.val + 1;
    					}
    				}
    				break;
    			}
    			l2 = l2.next;
    		}
    	} else {
    		listNodeHead = l2;
    		while(true){
    			tempNum = l1.val + l2.val + flag;
    			l2.val =tempNum%10;
    			System.out.println("listNodeHead = l2 numtemp = " + l2.val );
    			flag = tempNum > 9 ? 1 : 0;     			
    			//l1 = l1.next;
    			if(l1.next == null){
    				if( flag == 1) {
    					if(l2.next ==null) {
    						l2.next = new ListNode(1);
    					} else {
    						l2.next.val = l2.next.val + 1;
    					}
    				}
    				break;
    			}
    			l2 = l2.next;
    		}
    	}
    	myPrint(listNodeHead);
        return listNodeHead;
    }

    
    public static void main(String[] args) {
    	ListNode l1 = new ListNode(2);
    	ListNode nodea1 = new ListNode(4);
    	l1.next = nodea1;
    	ListNode nodea2 = new ListNode(3);
    	nodea1.next = nodea2;
    	
    	ListNode l2 = new ListNode(5);
    	ListNode nodeb1 = new ListNode(6);
    	l2.next = nodeb1;
    	ListNode nodeb2 = new ListNode(4);
    	nodeb1.next = nodeb2;
    	
    	//addTwoNumbers(l1, l2);
    	addTwoNumbers2(l1, l2);
    	
	}
    
    static void myPrint(ListNode l) {
    	ListNode temp = l;
        do {
     		System.out.print(temp.val + ", ");
     		if (temp.next == null)
     			break;
     		temp =temp.next;
 		} while(true);
     	
     	System.out.println();
    }
}

