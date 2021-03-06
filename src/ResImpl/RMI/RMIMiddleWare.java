package ResImpl.RMI;

import java.rmi.ConnectException;
import java.rmi.NoSuchObjectException;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.List;
import java.util.Random;
import java.util.Vector;
import java.util.Timer;
import java.util.HashMap;
import java.util.Map;

import FaultTolerance.CrashException;
import FaultTolerance.ICrashable;
import FaultTolerance.Suspect;
import LockManager.DeadlockException;
import ResImpl.AbstractResourceManager;
import ResImpl.Car;
import ResImpl.CarResourceManager;
import ResImpl.Customer;
import ResImpl.CustomerResourceManager;
import ResImpl.Flight;
import ResImpl.Hotel;
import ResImpl.RMHashtable;
import ResImpl.ReservableItem;
import ResImpl.ReservedItem;
import ResImpl.Trace;
import ResInterface.CarFrontend;
import ResInterface.FlightFrontend;
import ResInterface.ICarResourceManager;
import ResInterface.IFlightResourceManager;
import ResInterface.IMiddleWare;
import ResInterface.IRoomResourceManager;
import ResInterface.ReservationFrontend;
import ResInterface.ResourceFrontend;
import ResInterface.RoomFrontend;
import Transactions.AliveTransactionTask;
import Transactions.ITransactionManager;
import Transactions.InvalidTransactionException;
import Transactions.TransactionAbortedException;
import Transactions.TransactionException;
import Transactions.TransactionManager;

public class RMIMiddleWare extends AbstractRMIResourceManager implements Remote, IMiddleWare {

	private ICarResourceManager carRM;
	private IFlightResourceManager flightRM;
	private IRoomResourceManager roomRM;
	private CustomerResourceManager customerRM;
	
	private List<ICarResourceManager> carRMs;
	private List<IFlightResourceManager> flightRMs;
	private List<IRoomResourceManager> roomRMs;
	
	private Hashtable<Object, String> hosts;
	private Hashtable<Object, Integer> ports;
	
	private List<Suspect> suspectedCrashed;
	
	private int txnId = 0;
	private AliveTransactionTask aliveTransactionTask;
	private Timer alive;
	private Map<Integer, Long> transactions = new HashMap<Integer, Long>();

	// By default, if there is no args for car/room/flight, we try
	// localhost:1099
	// Explicit is better than implicit
	private String[] carservers;
	private String[] flightservers;
	private String[] roomservers;
	private int[] carports;
	private int[] flightports;
	private int[] roomports;

	public RMIMiddleWare() {
		super();
		aliveTransactionTask = new AliveTransactionTask(transactions, this);
		alive = new Timer();
		alive.schedule(aliveTransactionTask, AliveTransactionTask.TRANSACTION_TIMEOUT_SECONDS, AliveTransactionTask.TRANSACTION_TIMEOUT_SECONDS);
		
		carRMs = new ArrayList<ICarResourceManager>();
		flightRMs = new ArrayList<IFlightResourceManager>();
		roomRMs = new ArrayList<IRoomResourceManager>();
		
		hosts = new Hashtable<Object, String>();
		ports = new Hashtable<Object, Integer>();
		
		suspectedCrashed = new ArrayList<Suspect>();
		
	}

	public synchronized CarFrontend getCarResourceManager() {
		return carRM;
	}

	public synchronized FlightFrontend getFlightResourceManager() {
		return flightRM;
	}

	public synchronized RoomFrontend getRoomResourceManager() {
		return roomRM;
	}
	
	public synchronized CustomerResourceManager getCustomerResourceManager(){
		return customerRM;
	}

	public synchronized void setCarResourceManager(CarRMIResourceManager carRM) {
		this.carRM = carRM;
	}

	public synchronized void setFlightResourceManager(FlightRMIResourceManager flightRM) {
		this.flightRM = flightRM;
	}

	public synchronized void setRoomResourceManager(RoomRMIResourceManager roomRM) {
		this.roomRM = roomRM;
	}

	@Override
	public synchronized boolean addFlight(int id, int flightNum, int flightSeats, int flightPrice) throws RemoteException {

		boolean success = true;
		keepAlive(id);
		for (int i = flightRMs.size() - 1; i >= 0; i--){
			try {
				success &= flightRMs.get(i).addFlight(id, flightNum, flightSeats, flightPrice);
				if (!success){
					for (int j = flightRMs.size() - 1; j > i; j--){
						flightRMs.get(j).undoLast(id);
					}
					break;
				}
					
			} catch (RemoteException e){
				handleFlightRMCrash(flightRMs.get(i));
			} catch (DeadlockException e) {
				abort(id);
				success = false;
			} catch (InvalidTransactionException e) {
				Trace.error("[ERROR] "+e.getMessage());
				success = false;
			}
		}
			
		return success;
	}

	@Override
	public synchronized boolean deleteFlight(int id, int flightNum) throws RemoteException {

		boolean success = true;
		keepAlive(id);
		for (int i = flightRMs.size() - 1; i >= 0; i--){
			try {
				success &= flightRMs.get(i).deleteFlight(id, flightNum);
				if (!success){
					for (int j = flightRMs.size() - 1; j > i; j--){
						flightRMs.get(j).undoLast(id);
					}
					break;
				}
			} catch (RemoteException e){
				handleFlightRMCrash(flightRMs.get(i));
			} catch (InvalidTransactionException e) {
				Trace.error("[ERROR] "+e.getMessage());
				success = false;
			} catch (DeadlockException e) {
				abort(id);
				success = false;
			} 
		}
		
		return success;
	}

	@Override
	public synchronized int queryFlight(int id, int flightNumber) throws RemoteException {
		try {
			keepAlive(id);
			int flight = flightRM.queryFlight(id, flightNumber);
			scheduleNextFlightRM();
			return flight;
		} catch (InvalidTransactionException e) {
			Trace.error("[ERROR] "+e.getMessage());
		} catch (DeadlockException e) {
			abort(id);
		} catch (RemoteException e){
			handleFlightRMCrash(flightRM);
			scheduleNextFlightRM();
			// retry the read
			if (flightRMs.size() > 0)
				return queryFlight(id, flightNumber);
		}
		
		return -1;
	}

	@Override
	public synchronized int queryFlightPrice(int id, int flightNumber) throws RemoteException {
		try {
			keepAlive(id);
			int price = flightRM.queryFlightPrice(id, flightNumber);
			scheduleNextFlightRM();
			return price;
		} catch (InvalidTransactionException e) {
			Trace.error("[ERROR] "+e.getMessage());
		} catch (DeadlockException e) {
			abort(id);
		} catch (RemoteException e){
			handleFlightRMCrash(flightRM);
			scheduleNextFlightRM();
			// retry the read
			if (flightRMs.size() > 0)
				return queryFlightPrice(id, flightNumber);
		}
		return -1;
	}

	@Override
	public synchronized boolean reserveFlight(int id, int customer, int flightNumber, boolean local) throws RemoteException {
		try {
			transactions.put(id, Calendar.getInstance().getTime().getTime());
			return customerRM.reserveFlight(id, customer, flightNumber, local);
		} catch (DeadlockException e) {
			abort(id);
		} catch (TransactionException e) {
			Trace.error("[ERROR] "+e.getMessage());	
		} catch (CrashException e){
			handleFlightRMCrash((IFlightResourceManager) e.getOffendingRM());
			if (e.shouldRetry()){
				return reserveFlight(id, customer, flightNumber, local);
			}
		} 
		return false;
	}

	@Override
	public synchronized boolean addRooms(int id, String location, int numRooms, int price) throws RemoteException {
		
		boolean success = true;
		keepAlive(id);
		for (int i = roomRMs.size() - 1; i >= 0; i--){
			try {
				success &= roomRMs.get(i).addRooms(id, location, numRooms, price);
				if (!success){
					for (int j = roomRMs.size() - 1; j > i; j--){
						roomRMs.get(j).undoLast(id);
					}
					break;
				}
			} catch (RemoteException e){
				handleRoomRMCrash(roomRMs.get(i));
			} catch (DeadlockException e) {
				System.out.println(this+":: DEADLOCK when adding room. txid: "+id);
				abort(id);
				success = false;
			} catch (InvalidTransactionException e) {
				Trace.error("[ERROR] "+e.getMessage());
				success = false;
			} 
		}
		
		return success;
	}

	@Override
	public synchronized boolean deleteRooms(int id, String location) throws RemoteException {

		boolean success = true;
		keepAlive(id);
		for (int i = roomRMs.size() - 1; i >= 0; i--){
			try {
				success &= roomRMs.get(i).deleteRooms(id, location);
				if (!success){
					for (int j = roomRMs.size() - 1; j > i; j--){
						roomRMs.get(j).undoLast(id);
					}
					break;
				}
			} catch (RemoteException e){
				handleRoomRMCrash(roomRMs.get(i));
			} catch (InvalidTransactionException e) {
				Trace.error("[ERROR] "+e.getMessage());
				success = false;
			} catch (DeadlockException e) {
				abort(id);
				success = false;
			}  
		}
		
		return success;
	}

	@Override
	public synchronized int queryRooms(int id, String location) throws RemoteException {
		try {
			keepAlive(id);
			int rooms = roomRM.queryRooms(id, location);
			scheduleNextRoomRM();
			return rooms;
		} catch (InvalidTransactionException e) {
			Trace.error("[ERROR] "+e.getMessage());
		} catch (DeadlockException e) {
			System.out.println(this+"::DEADLOCK. Aborting txid "+id);
			abort(id);
		} catch (RemoteException e){
			handleRoomRMCrash(roomRM);
			scheduleNextRoomRM();
			// retry the read
			if (roomRMs.size() > 0)
				return queryRooms(id, location);
		}
		
		return -1;
	}

	@Override
	public synchronized int queryRoomsPrice(int id, String location) throws RemoteException {
		try {
			keepAlive(id);
			int price = roomRM.queryRoomsPrice(id, location);
			scheduleNextRoomRM();
			return price;
		} catch (InvalidTransactionException e) {
			Trace.error("[ERROR] "+e.getMessage());
		} catch (DeadlockException e) {
			abort(id);
		} catch (RemoteException e){
			handleRoomRMCrash(roomRM);
			scheduleNextRoomRM();
			// retry the read
			if (roomRMs.size() > 0)
				return queryRoomsPrice(id, location);
		}
		
		return -1;
	}

	@Override
	public synchronized boolean reserveRoom(int id, int customer, String location, boolean local) throws RemoteException {
		try {
			transactions.put(id, Calendar.getInstance().getTime().getTime());
			return customerRM.reserveRoom(id, customer, location, local);
		} catch (DeadlockException e) {
			abort(id);
		} catch (InvalidTransactionException e) {
			Trace.error("[ERROR] "+e.getMessage());
		} catch (CrashException e){
			handleRoomRMCrash((IRoomResourceManager) e.getOffendingRM());
			if (e.shouldRetry())
				return reserveRoom(id, customer, location, local);
		}

		return false;
	}

	@Override
	public synchronized boolean addCars(int id, String location, int numCars, int price) throws RemoteException {
		
		boolean success = true;
		keepAlive(id);
		
		for (int i = carRMs.size() - 1; i >= 0; i--){
			try {
				success &= carRMs.get(i).addCars(id, location, numCars, price);
				if (!success){
					for (int j = carRMs.size() - 1; j > i; j--){
						carRMs.get(j).undoLast(id);
					}
					break;
				}
			} catch (RemoteException e){
				handleCarRMCrash(carRMs.get(i));
			} catch (InvalidTransactionException e) {
				Trace.error("[ERROR] "+e.getMessage());
				success = false;
			} catch (DeadlockException e) {
				abort(id);
				success = false;
			} 
		}
		
		return success;
	}

	@Override
	public synchronized boolean deleteCars(int id, String location) throws RemoteException {

		boolean success = true;
		keepAlive(id);
		for (int i = carRMs.size() - 1; i >= 0; i--){
			try {
				success &= carRMs.get(i).deleteCars(id, location);
				if (!success){
					for (int j = carRMs.size() - 1; j > i; j--){
						carRMs.get(j).undoLast(id);
					}
					break;
				}
			} catch (RemoteException e){
				handleCarRMCrash(carRMs.get(i));
			} catch (InvalidTransactionException e) {
				Trace.error("[ERROR] "+e.getMessage());
				success = false;
			} catch (DeadlockException e) {
				abort(id);
				success = false;
			} 
		}
		
		return success;
	}

	@Override
	public synchronized int queryCars(int id, String location) throws RemoteException {
		try {
			keepAlive(id);
			int cars = carRM.queryCars(id, location);
			scheduleNextCarRM();
			return cars;
		} catch (InvalidTransactionException e) {
			Trace.error("[ERROR] "+e.getMessage());
		} catch (DeadlockException e) {
			abort(id);
		} catch (RemoteException e){
			handleCarRMCrash(carRM);
			scheduleNextCarRM();
			// retry the read
			if (carRMs.size() > 0)
				return queryCars(id, location);
		}
		return -1;
	}

	@Override
	public synchronized int queryCarsPrice(int id, String location) throws RemoteException {
		try {
			keepAlive(id);
			int price = carRM.queryCarsPrice(id, location);
			scheduleNextCarRM();
			return price;
		} catch (InvalidTransactionException e) {
			Trace.error("[ERROR] "+e.getMessage());
		} catch (DeadlockException e) {
			abort(id);
		} catch (RemoteException e){
			handleCarRMCrash(carRM);
			scheduleNextCarRM();
			// retry the read
			if (carRMs.size() > 0)
				return queryCarsPrice(id, location);
		}
		
		return -1;
	}

	@Override
	public synchronized boolean reserveCar(int id, int customer, String location, boolean local)
			throws RemoteException {
		try {
			transactions.put(id, Calendar.getInstance().getTime().getTime());
			return customerRM.reserveCar(id, customer, location, local);
		} catch (InvalidTransactionException e) {
			Trace.error("[ERROR] "+e.getMessage());
		} catch (DeadlockException e) {
			abort(id);
		} catch (CrashException e){
			handleCarRMCrash((ICarResourceManager) e.getOffendingRM());
			if (e.shouldRetry())
				return reserveCar(id, customer, location, local);
		}
		return false;
	}

	@Override
	public synchronized int newCustomer(int id) throws RemoteException {
		try {
			keepAlive(id);
			return customerRM.newCustomer(id);
		} catch (DeadlockException e) {
			abort(id);
		} catch (InvalidTransactionException e) {
			Trace.error("[ERROR] "+e.getMessage());
		}
		return -1;
	}

	@Override
	public synchronized boolean newCustomer(int id, int customerID) throws RemoteException {
		try {
			keepAlive(id);
			return customerRM.newCustomer(id, customerID);
		} catch (DeadlockException e) {
			abort(id);
		} catch (InvalidTransactionException e) {
			Trace.error("[ERROR] "+e.getMessage());
		}
		return false;
	}

	@Override
	public synchronized boolean deleteCustomer(int id, int customerID) throws RemoteException {
		try {
			keepAlive(id);
			return customerRM.deleteCustomer(id, customerID);
		} catch (DeadlockException e) {
			abort(id);
		} catch (InvalidTransactionException e) {
			Trace.error("[ERROR] "+e.getMessage());
		} catch (CrashException e){
			handleCarRMCrash((ICarResourceManager) e.getOffendingRM());
		}
		return false;
	}

	@Override
	public synchronized String queryCustomerInfo(int id, int customerID) throws RemoteException {
		try {
			keepAlive(id);
			return customerRM.queryCustomerInfo(id, customerID);
		} catch (DeadlockException e) {
			abort(id);
		} catch (InvalidTransactionException e) {
			Trace.error("[ERROR] "+e.getMessage());
		}
		return "";
	}

	@Override
	public synchronized boolean itinerary(int id, int customer,
			Vector<String> flightNumbers, String location, boolean Car,
			boolean Room, boolean local) throws RemoteException {
		try {
			keepAlive(id);
			return customerRM.itinerary(id, customer, flightNumbers, location, Car, Room, local);
		} catch (NumberFormatException e) {
			
		} catch (InvalidTransactionException e) {
			Trace.error("[ERROR] "+e.getMessage());
		} catch (DeadlockException e) {
			abort(id);
		} catch (CrashException e){
			if (e.getOffendingRM() instanceof ICarResourceManager){
				handleCarRMCrash((ICarResourceManager) e.getOffendingRM());
			} else if (e.getOffendingRM() instanceof IFlightResourceManager){
				handleFlightRMCrash((IFlightResourceManager) e.getOffendingRM());
			} else if (e.getOffendingRM() instanceof IRoomResourceManager){
				handleRoomRMCrash((IRoomResourceManager) e.getOffendingRM());
			}
		}
		return false;
	}

	public synchronized String usage() {
		return "Usage: java MiddleWare [opts]\n"
				+ "where opts must include all of:\n\n" + "\t-car=[host]\n"
				+ "\t-flight=[host]\n" + "\t-room=[host]\n"
				+ "\t[-port=[host]]\n\n" + "and order does not matter";
	}

	/**
	 * Main entry point for the middle ware
	 * 
	 * @param args
	 *            command line arguments
	 * 
	 */
	public synchronized static void main(String[] args) {

		RMIMiddleWare mw = new RMIMiddleWare();
		mw.parseArgs(args);
		mw.launch();

	}

	@Override
	protected void parseArgs(String[] args) {

		if (args.length != 4 && args.length != 3) {
			System.err.println(usage());
			System.exit(1);
		}

		String[] valid = { "car", "flight", "room", "port" };
		String flag;

		for (String arg : args) {

			for (String s : valid) {
				flag = "-" + s + "=";
				if (flag.equals(arg.substring(0, flag.length()))
						&& arg.length() > flag.length()) {
					String argval = arg.split("=")[1];
					String[] set;
					// if : setting the port where this middleware runs
					// else : we need to parse the server name and server port
					if (s.equals("port")) {
						this.port = Integer.parseInt(argval);
					} else if (s.equals("car")) {
						set = argval.split(",");
						carservers = new String[set.length];
						carports = new int[set.length];
						for (int i = 0; i < set.length; i++){
							carservers[i] = set[i].split(":")[0];
							carports[i] = Integer.parseInt(set[i].split(":")[1]);
						}
					} else if (s.equals("room")) {
						set = argval.split(",");
						roomservers = new String[set.length];
						roomports = new int[set.length];
						for (int i = 0; i < set.length; i++){
							roomservers[i] = set[i].split(":")[0];
							roomports[i] = Integer.parseInt(set[i].split(":")[1]);
						}
					} else if (s.equals("flight")) {
						set = argval.split(",");
						flightservers = new String[set.length];
						flightports = new int[set.length];
						for (int i = 0; i < set.length; i++){
							flightservers[i] = set[i].split(":")[0];
							flightports[i] = Integer.parseInt(set[i].split(":")[1]);
						}
					}
				}
			}
		}
	}

	@Override
	protected void launch() {

		try {
			for (int i = 0; i < carservers.length; i++){
				ICarResourceManager rm = (ICarResourceManager) LocateRegistry.getRegistry(carservers[i],carports[i]).lookup("RMICar");
				hosts.put(rm, rm.getHost());
				ports.put(rm, rm.getPort());
				carRMs.add(rm);
			}
			carRM = carRMs.get(0);
			
			for (int i = 0; i < roomservers.length; i++){
				IRoomResourceManager rm = (IRoomResourceManager) LocateRegistry.getRegistry(roomservers[i], roomports[i]).lookup("RMIRoom");
				hosts.put(rm, rm.getHost());
				ports.put(rm, rm.getPort());
				roomRMs.add(rm);
			}
			roomRM = roomRMs.get(0);
			
			for (int i = 0; i < flightservers.length; i++){
				IFlightResourceManager rm = (IFlightResourceManager) LocateRegistry.getRegistry(flightservers[i], flightports[i]).lookup("RMIFlight");
				hosts.put(rm, rm.getHost());
				ports.put(rm, rm.getPort());
				flightRMs.add(rm);
			}
			flightRM = flightRMs.get(0);
			
			customerRM = new CustomerResourceManager(carRMs, flightRMs, roomRMs);
			customerRM.setFlightRM(flightRM);
			customerRM.setCarRM(carRM);
			customerRM.setRoomRM(roomRM);
			
		} catch (Exception e) {
			System.out.println("[ERROR] Middleware cannot get rmi object");
			e.printStackTrace();
			System.exit(1);
		}

		// Check if we have everything we need
		if (carRM == null) {
			System.out.println("Middleware was unable to establish a connection with the CarResourceManager");
		} else if (flightRM == null) {
			System.out.println("Middleware was unable to establish a connection with the FlightResourceManager");
		} else if (roomRM == null) {
			System.out.println("Middleware was unable to establish a connection with the RoomResourceManager");
		}

		// start his own rmi
		super.launch();
	}

	@Override
	protected void register() throws Exception {
		registry.bind("RMIMiddleware", UnicastRemoteObject.exportObject(this, 0));
	}
	
	@Override
	public synchronized int nextTransactionId() throws RemoteException{
		txnId++;
		keepAlive(txnId);
		return txnId;
	}

	@Override
	public synchronized int start() throws RemoteException, InvalidTransactionException {
	
		for (int i = carRMs.size() - 1; i >= 0; i--){
			try {
				carRMs.get(i).start();
			} catch (RemoteException e){
				handleCarRMCrash(carRMs.get(i));
			} 
		}
		
		for (int i = flightRMs.size() - 1; i >= 0; i--){
			try {
				flightRMs.get(i).start();
			} catch (RemoteException e){
				handleFlightRMCrash(flightRMs.get(i));
			}
		}
		
		for (int i = roomRMs.size() - 1; i >= 0; i--){
			try {
				roomRMs.get(i).start();
			} catch (RemoteException e){
				handleRoomRMCrash(roomRMs.get(i));
			}
		}
		
		return txnId;
	}

	@Override
	public synchronized boolean commitCustomerRM(int transactionId) throws RemoteException {	
		try {
			transactions.remove(transactionId);
			return customerRM.commit(transactionId);
		} catch (TransactionException e){
			return false; 
		}
	}
	
	@Override
	public synchronized boolean commit(int transactionId) throws RemoteException {
		boolean success = true;
		for (int i = carRMs.size() - 1; i >= 0; i--){
			try {
				success &= carRMs.get(i).commit(transactionId);
			} catch (RemoteException e){
				handleCarRMCrash(carRMs.get(i));
			} catch (TransactionException e){
				return false;
			}
		}
		
		for (int i = flightRMs.size() - 1; i >= 0; i--){
			try {
				success &= flightRMs.get(i).commit(transactionId);
			} catch (RemoteException e){
				handleFlightRMCrash(flightRMs.get(i));
			} catch (TransactionException e){
				return false;
			}
		}
		
		for (int i = roomRMs.size() - 1; i >= 0; i--){
			try {
				success &= roomRMs.get(i).commit(transactionId);
			} catch (RemoteException e){
				handleRoomRMCrash(roomRMs.get(i));
			} catch (TransactionException e){
				return false;
			}
		}
		
		if (!success)
			abort(transactionId);
		
		return success;
	}

	@Override
	public synchronized void abort(int transactionId) throws RemoteException{
		for (int i = carRMs.size() - 1; i >= 0; i--){
			try {
				carRMs.get(i).abort(transactionId);
			} catch (RemoteException e){
				handleCarRMCrash(carRMs.get(i));
			} catch (TransactionException e){
				
			}
		}
		
		for (int i = flightRMs.size() - 1; i >= 0; i--){
			try {
				flightRMs.get(i).abort(transactionId);
			} catch (RemoteException e){
				handleFlightRMCrash(flightRMs.get(i));
			} catch (TransactionException e){
				
			}
		}
		
		for (int i = roomRMs.size() - 1; i >= 0; i--){
			try {
				roomRMs.get(i).abort(transactionId);
			} catch (RemoteException e){
				handleRoomRMCrash(roomRMs.get(i));
			} catch (TransactionException e){
				 
			}
		}
	}

	@Override
	public synchronized boolean shutdown() throws RemoteException{
		boolean success = true;
		
		for (int i = carRMs.size() - 1; i >= 0; i--){
			try {
				success &= carRMs.get(i).shutdown();
			} catch (RemoteException e) {
				handleCarRMCrash(carRMs.get(i)); 
			}
		}
			
		
		for (int i = flightRMs.size() - 1; i >= 0; i--){
			try {
				success &= flightRMs.get(i).shutdown();
			} catch (RemoteException e){
				handleFlightRMCrash(flightRMs.get(i));
			}
		}
		
		for (int i = roomRMs.size() - 1; i >= 0; i--){
			try {
				success &= roomRMs.get(i).shutdown();
			} catch (RemoteException e){
				handleRoomRMCrash(roomRMs.get(i));
			}
		}
		
		return success;
	}

	@Override
	public synchronized boolean enlist(int transactionId) throws RemoteException {
		
		boolean success = true;
			int i ;

		for (i = carRMs.size() - 1; i >= 0; i--){
			try {
				success &= carRMs.get(i).enlist(transactionId);
			} catch (RemoteException e) {
				handleCarRMCrash(carRMs.get(i)); 
			} catch (TransactionException e){
				success = false;
			}
		}

		for (i = flightRMs.size() - 1; i >= 0; i--){
			try {
				success &= flightRMs.get(i).enlist(transactionId);
			} catch (RemoteException e) {
				handleFlightRMCrash(flightRMs.get(i));
			} catch (TransactionException e){
				success = false;
			}
		}

		for (i = roomRMs.size() - 1; i >= 0; i--){
			try {
				success &= roomRMs.get(i).enlist(transactionId);
			} catch (RemoteException e) {
				handleRoomRMCrash(roomRMs.get(i));
			} catch (TransactionException e){
				success = false;
			}
		}

		if (!success) {
			abort(transactionId);
		}
		
		return success;
	}
	
	/**
	 * Assign the next car resource manager to read from 
	 * 
	 * For now, uses simple round robin technique 
	 */
	public synchronized void scheduleNextCarRM(){
		if (carRMs.size() > 0){
			int idx = carRMs.indexOf(carRM);
			if (idx < carRMs.size() - 1)
				idx++;
			else
				idx = 0;
			
			carRM = carRMs.get(idx);
			customerRM.setCarRM(carRM);
		}
	}
	
	
	/**
	 * Assign the next flight resource manager to read from 
	 * 
	 * For now, uses simple round robin technique 
	 */
	public synchronized void scheduleNextFlightRM(){
		if (flightRMs.size() > 0){
			int idx = flightRMs.indexOf(flightRM);
			if (idx < flightRMs.size() - 1)
				idx++;
			else
				idx = 0;
			
			flightRM = flightRMs.get(idx);
			customerRM.setFlightRM(flightRM);
		}
	}
	
	/**
	 * Assign the next room resource manager to read from 
	 * 
	 * For now, uses simple round robin technique 
	 */
	public synchronized void scheduleNextRoomRM(){
		if (roomRMs.size() > 0){
			int idx = roomRMs.indexOf(roomRM);
			if (idx < roomRMs.size() - 1)
				idx++;
			else
				idx = 0;
			
			roomRM = roomRMs.get(idx);
			customerRM.setRoomRM(roomRM);
		}
	}
	
	/**
	 * Called whenever a ConnectException is thrown because
	 * a flight resource manager is no longer connected. 
	 * @param e the original exception 
	 */
	public synchronized void handleFlightRMCrash(IFlightResourceManager flightRM){
		if (flightRMs.size() > 0)
			Trace.error("[ERROR] One of the flight resource managers crashed ... ");
		else
			Trace.error("[ERROR] No more flight resource managers alive ... ");
		this.flightRMs.remove(flightRM);
		this.suspectedCrashed.add(new Suspect(hosts.get(flightRM), ports.get(flightRM), Suspect.FLIGHT));
	}
	
	/**
	 * Called whenever a ConnectException is thrown because
	 * a car resource manager is no longer connected. 
	 * @param e the original exception 
	 */
	public synchronized void handleCarRMCrash(ICarResourceManager carRM){
		if (carRMs.size() > 0)
			Trace.error("[ERROR] One of the car resource managers crashed ... ");
		else
			Trace.error("[ERROR] No more car resource managers alive ... ");
		this.carRMs.remove(carRM);
		this.suspectedCrashed.add(new Suspect(hosts.get(carRM), ports.get(carRM), Suspect.CAR));
	}
	
	/**
	 * Called whenever a ConnectException is thrown because
	 * a room resource manager is no longer connected. 
	 * @param e the original exception 
	 */
	public synchronized void handleRoomRMCrash(IRoomResourceManager roomRM){
		if (roomRMs.size() > 0)
			Trace.error("[ERROR] One of the room resource managers crashed ... ");
		else
			Trace.error("[ERROR] No more room resource managers alive ... ");
		this.roomRMs.remove(roomRM);
		this.suspectedCrashed.add(new Suspect(hosts.get(roomRM), ports.get(roomRM), Suspect.ROOM));
	}

	@Override
	public synchronized void crashHost(String host, int num) throws RemoteException {
		for (Object o : hosts.keySet()){
			if (hosts.get(o).equalsIgnoreCase(host)){
				((ICrashable) o).crash();
				if (o instanceof ICarResourceManager)
					carRMs.remove(o);
				else if (o instanceof IFlightResourceManager)
					flightRMs.remove(o);
				else if (o instanceof IRoomResourceManager)
					roomRMs.remove(o);
				num--;
				if (num == 0)
					break;
			}
		}
	}

	@Override
	public synchronized void crashType(String type, int num) throws RemoteException {
		Random rand = new Random();
		for (int i = 0; i < num; i++){
			if (type.equalsIgnoreCase("car")){
				if (carRMs.size() > 0){
					ICarResourceManager rm = carRMs.remove(rand.nextInt(carRMs.size()));
					rm.crash();
				}
			} else if (type.equalsIgnoreCase("flight")){
				if (flightRMs.size() > 0){
					IFlightResourceManager rm = flightRMs.remove(rand.nextInt(flightRMs.size()));
					rm.crash();
				}
			} else if (type.equalsIgnoreCase("room")){
				if (roomRMs.size() > 0){
					IRoomResourceManager rm = roomRMs.remove(rand.nextInt(roomRMs.size()));
					rm.crash();
				}
			} 
		}
	}


	@Override
	protected void unregister() throws Exception {
		UnicastRemoteObject.unexportObject(this, true);
		registry.unbind("RMIMiddleware");
	}

	@Override
	public synchronized void undoLast(int id) throws RemoteException, InvalidTransactionException {
		customerRM.undoLast(id);
	}

	@Override
	public synchronized void keepAlive(int id) throws RemoteException {
		transactions.put(id, Calendar.getInstance().getTime().getTime());
	}

	@Override
	public synchronized int startCustomerRM() throws RemoteException {
		return customerRM.start();
	}

	@Override
	public synchronized boolean enlistCustomerRM(int id) throws RemoteException {
		try {
			return customerRM.enlist(id);
		} catch (InvalidTransactionException e) {
			return false;
		}
	}

	@Override
	public synchronized void abortCustomerRM(int transactionId) throws RemoteException {
		try {
			transactions.remove(transactionId);
			customerRM.abort(transactionId);
		} catch (InvalidTransactionException e) {
			e.printStackTrace();
		}
	}

	@Override
	public synchronized boolean shutdownCustomerRM() throws RemoteException {
		transactions.clear();
		return customerRM.shutdown();
	}
}
