package ResInterface;

import java.rmi.RemoteException;

import LockManager.DeadlockException;
import ResImpl.Car;
import Transactions.InvalidTransactionException;

public interface CarBackend {

	 public Car getCar(int id, String location) throws RemoteException, DeadlockException, InvalidTransactionException;
	    
	 public void updateCar(int id, String location, Car car) throws RemoteException, DeadlockException, InvalidTransactionException;
		
}
