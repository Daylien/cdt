#ifndef INCLUDE_H
#define INCLUDE_H

class Head {
	Head * operator *= ( int index );
	Head * operator *  ( int index ){ return array[ index ]; }
	Head * operator += ( int index );
	
	Head ** array;
};

#endif