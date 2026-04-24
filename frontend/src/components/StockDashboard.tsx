import React, { useState, useEffect, useRef } from 'react';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { Play, Settings, Activity, List, TrendingUp, BarChart3, ChevronRight, Search, X, Download, Target, ChevronDown, RefreshCw } from 'lucide-react';
import { BarChart, Bar, XAxis, Tooltip, ResponsiveContainer, Cell } from 'recharts';
import StockChart from './StockChart';
import ConfigPanel from './ConfigPanel';
import SectorHeatmap from './SectorHeatmap';

interface AnalysisUpdate {
    type: 'STATUS' | 'PROGRESS' | 'RESULTS' | 'ERROR' | 'ML_FEATURES' | 'BACKTEST_REPORT';
    payload: any;
    timestamp: number;
}

interface Profile {
    id: number;
    name: string;
    description: string;
    configJson: string;
}

const StockDashboard: React.FC = () => {
    const [status, setStatus] = useState<string>('Idle');
    const [progress, setProgress] = useState<string>('');
    const [percent, setPercent] = useState<number>(0);
    const [logs, setLogs] = useState<string[]>([]);
    const [results, setResults] = useState<any[]>([]);
    const [backtestReport, setBacktestReport] = useState<any>(null);
    const [featureImportance, setFeatureImportance] = useState<any[]>([]);
    const [isRunning, setIsRunning] = useState(false);
    const [selectedStock, setSelectedStock] = useState<any>(null);
    const [comparisonTickers, setComparisonTickers] = useState<string[]>([]);
    const [comparisonData, setComparisonData] = useState<any[]>([]);
    const [compSearch, setCompSearch] = useState('');
    const [showConfig, setShowConfig] = useState(false);
    const [searchTerm, setSearchTerm] = useState('');
    const [profiles, setProfiles] = useState<Profile[]>([]);
    const [currentConfig, setCurrentConfig] = useState<any>(() => {
        const saved = localStorage.getItem('stockAnalyzerConfig');
        if (saved) {
            try { return JSON.parse(saved); } catch (e) {}
        }
        return {
            startTimes: [50, 110, 200],
            selectTimes: [30],
            searchTimes: [30, 80, 130],
            longMovingAvgTimes: [140],
            sellCutOffPerc: [0.93],
            lowerPriceToLongAvgBuyIn: [0.92],
            higherPriceToLongAvgBuyIn: [1.02],
            timeFrameForUpwardLongAvg: [40],
            timeFrameForOscillator: [110],
            maxPERatios: [25],
            aboveAvgRatingPricePerc: [1.0],
            timeFrameForUpwardShortPrice: [1],
            maxRSI: [100.0],
            minMarketCap: [1300.0],
            maxMarketCap: [2750.0],
            minRatesOfAvgInc: [1.1],
            minRatings: [3.75],
            maxRatings: [4.6],
            riskFreeRate: [0.05],
            movingAvgGapWeight: [0.20],
            reversionToMeanWeight: [0.15],
            ratingWeight: [0.20],
            upwardIncRateWeight: [0.15],
            rvolWeight: [0.10],
            pegWeight: [0.10],
            volatilityCompressionWeight: [0.10],
            sectors: [101010, 101020, 151010, 151020, 151030, 151050],
            exchanges: ["TASE", "NYSE", "NasdaqGS"],
            outputPath: "output"
        };
    });
    const stompClient = useRef<Client | null>(null);

    useEffect(() => {
        localStorage.setItem('stockAnalyzerConfig', JSON.stringify(currentConfig));
    }, [currentConfig]);

    useEffect(() => {
        const client = new Client({
            webSocketFactory: () => new SockJS('http://localhost:8080/ws-stock'),
            onConnect: () => {
                client.subscribe('/topic/updates', (message) => {
                    const update: AnalysisUpdate = JSON.parse(message.body);
                    handleUpdate(update);
                });
            },
        });
        client.activate();
        stompClient.current = client;
        
        fetchProfiles();
        
        return () => {
            client.deactivate();
        };
    }, []);

    const fetchProfiles = async () => {
        try {
            const res = await fetch('http://localhost:8080/api/profiles');
            const data = await res.json();
            setProfiles(data);
        } catch (error) {
            console.error('Failed to fetch profiles', error);
        }
    };

    const handleUpdate = (update: AnalysisUpdate) => {
        const time = new Date(update.timestamp).toLocaleTimeString();
        switch (update.type) {
            case 'STATUS':
                setStatus(update.payload);
                setLogs(prev => [`[${time}] ${update.payload}`, ...prev]);
                break;
            case 'PROGRESS':
                setProgress(update.payload);
                const match = update.payload.match(/(\d+)%/);
                if (match) setPercent(parseInt(match[1]));
                break;
            case 'ML_FEATURES':
                setFeatureImportance(update.payload);
                break;
            case 'BACKTEST_REPORT':
                setBacktestReport(update.payload);
                setIsRunning(false);
                setPercent(100);
                break;
            case 'RESULTS':
                setResults(update.payload);
                setIsRunning(false);
                setPercent(100);
                break;
            case 'ERROR':
                setLogs(prev => [`[${time}] ERROR: ${update.payload}`, ...prev]);
                setIsRunning(false);
                break;
        }
    };

    const runBacktest = async (configToUse: any) => {
        setIsRunning(true);
        setBacktestReport(null);
        setLogs([]);
        setPercent(0);
        try {
            await fetch('http://localhost:8080/api/analysis/backtest', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(configToUse)
            });
        } catch (error) {
            setIsRunning(false);
        }
    };

    const runOpportunities = async (configToUse: any) => {
        setIsRunning(true);
        setLogs([]);
        setPercent(0);
        try {
            await fetch('http://localhost:8080/api/analysis/opportunities', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(configToUse)
            });
        } catch (error) {
            setIsRunning(false);
        }
    };


    const startAnalysis = async () => {
        setIsRunning(true);
        setResults([]);
        setLogs([]);
        setPercent(0);
        try {
            const response = await fetch('http://localhost:8080/api/analysis/run', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(currentConfig)
            });
            if (!response.ok) {
                const errorData = await response.json();
                setLogs(prev => [`[${new Date().toLocaleTimeString()}] ERROR: ${errorData.message || 'Validation failed'}`, ...prev]);
                setIsRunning(false);
            }
        } catch (error) {
            setIsRunning(false);
        }
    };

    const selectProfile = (profile: Profile) => {
        try {
            setCurrentConfig(JSON.parse(profile.configJson));
        } catch (e) {
            console.error('Failed to parse profile config');
        }
    };

    const exportParams = async () => {
        try {
            const res = await fetch('http://localhost:8080/api/analysis/export-params');
            const data = await res.json();
            if (data.content) {
                const blob = new Blob([data.content], { type: 'text/yaml' });
                const url = window.URL.createObjectURL(blob);
                const a = document.createElement('a');
                a.href = url;
                a.download = 'best_params.yaml';
                a.click();
            } else {
                setLogs(prev => [`[${new Date().toLocaleTimeString()}] ERROR: ${data.error}`, ...prev]);
            }
        } catch (error) {
            console.error('Export failed', error);
        }
    };

    const savePreset = async (name: string, description: string, config: any) => {
        try {
            await fetch('http://localhost:8080/api/profiles', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    name,
                    description,
                    configJson: JSON.stringify(config)
                })
            });
            fetchProfiles();
        } catch (error) {
            console.error('Failed to save preset', error);
        }
    };

    const addComparison = async (ticker: string) => {
        if (!ticker || comparisonTickers.includes(ticker.toUpperCase())) return;
        try {
            const res = await fetch(`http://localhost:8080/api/stocks/${ticker.toUpperCase()}/graph`);
            const data = await res.json();
            if (data) {
                setComparisonData(prev => [...prev, data]);
                setComparisonTickers(prev => [...prev, ticker.toUpperCase()]);
                setCompSearch('');
            }
        } catch (error) {
            console.error('Failed to add comparison', error);
        }
    };

    const removeComparison = (ticker: string) => {
        setComparisonTickers(prev => prev.filter(t => t !== ticker));
        setComparisonData(prev => prev.filter(d => d.stock.ticker_symbol !== ticker));
    };

    const filteredResults = results.filter(res => 
        res.stock.stock.ticker_symbol.toLowerCase().includes(searchTerm.toLowerCase()) ||
        res.stock.stock.name.toLowerCase().includes(searchTerm.toLowerCase())
    );

    return (
        <div className="min-h-screen bg-[#0f172a] text-slate-200 font-sans">
            {showConfig && (
                <ConfigPanel 
                    config={currentConfig} 
                    onSave={setCurrentConfig} 
                    onClose={() => setShowConfig(false)} 
                    profiles={profiles}
                    onSavePreset={savePreset}
                />
            )}
            
            {/* Sidebar */}
            <aside className="fixed left-0 top-0 h-full w-20 bg-[#1e293b] border-r border-slate-800 flex flex-col items-center py-8 gap-8 z-50">
                <div className="w-12 h-12 bg-blue-600 rounded-xl flex items-center justify-center shadow-lg shadow-blue-900/20">
                    <TrendingUp className="text-white" size={28} />
                </div>
                <nav className="flex flex-col gap-6">
                    <button className="p-3 text-blue-400 bg-blue-400/10 rounded-xl transition-all shadow-lg shadow-blue-500/10"><Activity size={24} /></button>
                    <button onClick={() => setShowConfig(true)} className="p-3 text-slate-500 hover:text-slate-300 transition-colors"><Settings size={24} /></button>
                </nav>
            </aside>

            <main className="pl-28 pr-8 py-8 max-w-[1600px] mx-auto">
                <header className="mb-10 flex justify-between items-end">
                    <div>
                        <h1 className="text-3xl font-bold text-white mb-1 tracking-tight">Market Intelligence Suite</h1>
                        <div className="flex items-center gap-4 mt-2">
                            <div className="relative group">
                                <button className="flex items-center gap-2 px-3 py-1.5 rounded-lg bg-slate-800 border border-slate-700 text-sm text-slate-300 hover:bg-slate-700 transition-all">
                                    Strategy: <span className="text-blue-400 font-bold">{profiles.find(p => JSON.stringify(JSON.parse(p.configJson)) === JSON.stringify(currentConfig))?.name || 'Custom'}</span>
                                    <ChevronDown size={14} />
                                </button>
                                <div className="absolute top-full left-0 mt-2 w-72 bg-[#1e293b] border border-slate-700 rounded-xl shadow-2xl opacity-0 invisible group-hover:opacity-100 group-hover:visible transition-all z-[60]">
                                    <div className="p-2 space-y-1">
                                        {profiles.map(p => (
                                            <div key={p.id} className="group/item relative rounded-lg hover:bg-slate-800 transition-colors">
                                                <button 
                                                    onClick={() => selectProfile(p)}
                                                    className="w-full text-left px-4 py-3 flex flex-col gap-1 pr-12"
                                                >
                                                    <span className="text-white font-bold text-sm">{p.name}</span>
                                                    <span className="text-slate-500 text-[10px] line-clamp-1">{p.description}</span>
                                                </button>
                                                <button 
                                                    onClick={(e) => { e.stopPropagation(); runBacktest(JSON.parse(p.configJson)); }}
                                                    title="Run Backtest (Last 100 Days)"
                                                    className="absolute right-2 top-1/2 -translate-y-1/2 p-2 text-slate-500 hover:text-blue-400 opacity-0 group-hover/item:opacity-100 transition-all"
                                                >
                                                    <RefreshCw size={16} />
                                                </button>
                                            </div>
                                        ))}
                                    </div>
                                </div>
                            </div>
                            <button onClick={() => setShowConfig(true)} className="text-slate-500 hover:text-blue-400 text-sm flex items-center gap-1 transition-colors">
                                <Settings size={14} /> Edit Parameters
                            </button>
                        </div>
                    </div>
                    <div className="flex gap-4">
                        <button 
                            onClick={() => runBacktest(currentConfig)}
                            disabled={isRunning}
                            className="flex items-center gap-2 px-4 py-2 rounded-lg bg-slate-800 text-slate-300 hover:bg-slate-700 transition-colors disabled:opacity-50"
                        >
                            <RefreshCw size={18} className={isRunning ? 'animate-spin' : ''} /> 
                            {isRunning ? 'Running...' : 'Backtest Current'}
                        </button>
                        <button 
                            onClick={() => runOpportunities(currentConfig)}
                            disabled={isRunning}
                            className="flex items-center gap-2 px-4 py-2 rounded-lg bg-slate-800 text-slate-300 hover:bg-slate-700 transition-colors disabled:opacity-50"
                        >
                            <RefreshCw size={18} className={isRunning ? 'animate-spin' : ''} /> 
                            {isRunning ? 'Running...' : 'Find Opportunities'}
                        </button>
                        <button 
                            onClick={exportParams}
                            className="flex items-center gap-2 px-4 py-2 rounded-lg bg-slate-800 text-slate-300 hover:bg-slate-700 transition-colors"
                        >
                            <Download size={18} /> Export Best Params
                        </button>
                        <button 
                            onClick={startAnalysis}
                            disabled={isRunning}
                            className={`group relative flex items-center gap-3 px-8 py-4 rounded-xl font-bold text-lg overflow-hidden transition-all ${
                                isRunning ? 'bg-slate-800 text-slate-500' : 'bg-blue-600 text-white hover:bg-blue-500 hover:scale-[1.02] active:scale-95 shadow-lg shadow-blue-600/20'
                            }`}
                        >
                            <Play size={22} fill="currentColor" />
                            {isRunning ? 'Processing...' : 'Execute Analysis'}
                        </button>
                    </div>
                </header>

                {backtestReport && (
                    <div className="mb-8 p-8 bg-gradient-to-br from-indigo-950/40 to-slate-900 rounded-2xl border border-indigo-500/20 shadow-2xl animate-in zoom-in-95 duration-300">
                        <div className="flex justify-between items-start mb-8">
                            <div>
                                <h2 className="text-2xl font-black text-white tracking-tight flex items-center gap-3">
                                    <RefreshCw className="text-blue-400" size={24} />
                                    100-Day Historical Backtest Report
                                </h2>
                                <p className="text-slate-400 text-sm mt-1">Based on active strategy parameters and dual-engine scoring</p>
                            </div>
                            <button onClick={() => setBacktestReport(null)} className="p-1 hover:bg-slate-800 rounded text-slate-500 hover:text-white"><X size={20}/></button>
                        </div>
                        
                        <div className="grid grid-cols-4 gap-6 mb-8">
                            <div className="bg-slate-900/50 p-6 rounded-xl border border-slate-800">
                                <span className="text-slate-500 text-[10px] font-black uppercase tracking-widest block mb-1">Total Trades</span>
                                <span className="text-3xl font-mono font-bold text-white">{backtestReport.totalTrades}</span>
                            </div>
                            <div className="bg-slate-900/50 p-6 rounded-xl border border-slate-800">
                                <span className="text-slate-500 text-[10px] font-black uppercase tracking-widest block mb-1">Cumulative Gain</span>
                                <span className={`text-3xl font-mono font-bold ${backtestReport.totalGain.startsWith('-') ? 'text-red-400' : 'text-emerald-400'}`}>
                                    {backtestReport.totalGain}
                                </span>
                            </div>
                            <div className="bg-slate-900/50 p-6 rounded-xl border border-slate-800">
                                <span className="text-slate-500 text-[10px] font-black uppercase tracking-widest block mb-1">Avg Gain / Trade</span>
                                <span className={`text-3xl font-mono font-bold ${backtestReport.avgGain.startsWith('-') ? 'text-red-400' : 'text-emerald-400'}`}>
                                    {backtestReport.avgGain}
                                </span>
                            </div>
                            <div className="bg-slate-900/50 p-6 rounded-xl border border-slate-800">
                                <span className="text-slate-500 text-[10px] font-black uppercase tracking-widest block mb-1">Success Signal</span>
                                <span className="text-3xl font-mono font-bold text-blue-400">Stable</span>
                            </div>
                        </div>

                        <div className="bg-slate-950/50 rounded-xl border border-slate-800 overflow-hidden">
                            <table className="w-full text-sm">
                                <thead>
                                    <tr className="bg-slate-900/50 text-slate-500 text-[10px] font-black uppercase tracking-widest text-left">
                                        <th className="px-6 py-3">Date</th>
                                        <th className="px-6 py-3">Ticker</th>
                                        <th className="px-6 py-3">Duration</th>
                                        <th className="px-6 py-3 text-right">Result</th>
                                    </tr>
                                </thead>
                                <tbody className="divide-y divide-slate-800/50 font-mono">
                                    {backtestReport.trades.map((t: any, i: number) => (
                                        <tr key={i} className="hover:bg-white/5">
                                            <td className="px-6 py-3 text-slate-400">{t.buyDate}</td>
                                            <td className="px-6 py-3 text-white font-bold">{t.ticker}</td>
                                            <td className="px-6 py-3 text-slate-500">{t.days} Days</td>
                                            <td className={`px-6 py-3 text-right font-bold ${t.lastGained > 0 ? 'text-emerald-400' : 'text-red-400'}`}>
                                                {(t.lastGained * 100).toFixed(2)}%
                                            </td>
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                        </div>
                    </div>
                )}

                <div className="grid grid-cols-12 gap-6 mb-8">
                    {/* Status & Progress */}
                    <div className="col-span-12 lg:col-span-4 space-y-6">
                        <div className="bg-[#1e293b] p-6 rounded-2xl border border-slate-800 shadow-xl">
                            <h3 className="text-slate-400 text-xs font-bold uppercase tracking-widest mb-4">Pipeline Status</h3>
                            <div className="flex items-center justify-between mb-2">
                                <span className="text-xl font-semibold text-white tracking-wide">{status}</span>
                                <span className="text-blue-400 font-mono text-lg">{percent}%</span>
                            </div>
                            <div className="w-full h-3 bg-slate-900 rounded-full overflow-hidden mb-4 p-0.5">
                                <div 
                                    className="h-full bg-gradient-to-r from-blue-600 to-blue-400 rounded-full transition-all duration-700 ease-in-out shadow-[0_0_15px_rgba(59,130,246,0.5)]"
                                    style={{ width: `${percent}%` }}
                                ></div>
                            </div>
                            <p className="text-slate-400 text-sm italic opacity-80">{progress || 'Idle'}</p>
                        </div>

                        <div className="bg-[#1e293b] p-6 rounded-2xl border border-slate-800 shadow-xl h-[320px] flex flex-col">
                            <div className="flex items-center gap-2 mb-6">
                                <BarChart3 className="text-purple-400" size={20} />
                                <h3 className="text-white font-semibold tracking-wide">AI Feature Importance</h3>
                            </div>
                            <div className="flex-1 min-h-0">
                                <ResponsiveContainer width="100%" height="100%">
                                    <BarChart data={featureImportance.length ? featureImportance : [
                                        {name: 'MA Gap', val: 0.25}, {name: 'Momentum', val: 0.2}, 
                                        {name: 'RVOL', val: 0.15}, {name: 'Rating', val: 0.3}, {name: 'PEG', val: 0.1}
                                    ]}>
                                        <XAxis dataKey="name" hide />
                                        <Tooltip 
                                            contentStyle={{ backgroundColor: '#0f172a', border: 'none', borderRadius: '8px', boxShadow: '0 10px 15px -3px rgba(0,0,0,0.5)' }}
                                            itemStyle={{ color: '#94a3b8' }}
                                            cursor={{ fill: 'rgba(255,255,255,0.05)' }}
                                        />
                                        <Bar dataKey="val" radius={[6, 6, 0, 0]}>
                                            {featureImportance.map((_, index) => (
                                                <Cell key={`cell-${index}`} fill={['#3b82f6', '#8b5cf6', '#ec4899', '#f59e0b', '#10b981'][index % 5]} />
                                            ))}
                                        </Bar>
                                    </BarChart>
                                </ResponsiveContainer>
                            </div>
                        </div>
                    </div>

                    {/* Live Console */}
                    <div className="col-span-12 lg:col-span-8 bg-[#020617] rounded-2xl border border-slate-800 overflow-hidden flex flex-col shadow-2xl">
                        <div className="bg-slate-900/50 px-6 py-3 border-b border-slate-800 flex items-center justify-between">
                            <div className="flex gap-1.5">
                                <div className="w-3 h-3 rounded-full bg-red-500/20 border border-red-500/40"></div>
                                <div className="w-3 h-3 rounded-full bg-amber-500/20 border border-amber-500/40"></div>
                                <div className="w-3 h-3 rounded-full bg-emerald-500/20 border border-emerald-500/40"></div>
                            </div>
                            <span className="text-[10px] font-mono text-slate-500 uppercase tracking-[0.2em]">Execution Log stream</span>
                        </div>
                        <div className="p-6 font-mono text-sm overflow-y-auto max-h-[420px] scrollbar-thin scrollbar-thumb-slate-800">
                            {logs.map((log, i) => (
                                <div key={i} className="flex gap-4 mb-2 animate-in fade-in slide-in-from-left-2">
                                    <span className="text-slate-600 shrink-0 font-bold opacity-50">{(logs.length - i).toString().padStart(3, '0')}</span>
                                    <span className={log.includes('ERROR') ? 'text-red-400 font-bold' : log.includes('SUCCESS') ? 'text-emerald-400 font-bold' : 'text-slate-300'}>
                                        {log}
                                    </span>
                                </div>
                            ))}
                            {logs.length === 0 && (
                                <div className="flex flex-col items-center justify-center h-full text-slate-600 gap-4 opacity-50">
                                    <Target size={48} className="animate-pulse" />
                                    <p className="italic">System standby. Monitor ready for stream...</p>
                                </div>
                            )}
                        </div>
                    </div>
                </div>

                {/* Recommendations Table */}
                <div className="bg-[#1e293b] rounded-2xl border border-slate-800 shadow-2xl overflow-hidden mb-12">
                    <div className="px-8 py-6 border-b border-slate-800 flex items-center justify-between bg-slate-900/20">
                        <div className="flex items-center gap-3">
                            <div className="p-2 bg-emerald-500/10 rounded-lg text-emerald-400 shadow-inner">
                                <List size={22} />
                            </div>
                            <h2 className="text-xl font-bold text-white tracking-wide">AI-Ranked Opportunities</h2>
                        </div>
                        <div className="relative">
                            <Search className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-500" size={16} />
                            <input 
                                type="text" 
                                placeholder="Search tickers..." 
                                value={searchTerm}
                                onChange={(e) => setSearchTerm(e.target.value)}
                                className="bg-[#0f172a] border border-slate-800 rounded-lg pl-10 pr-4 py-2 text-sm focus:outline-none focus:border-blue-500 transition-all w-64"
                            />
                        </div>
                    </div>
                    <div className="overflow-x-auto">
                        <table className="w-full">
                            <thead>
                                <tr className="text-slate-500 text-xs font-bold uppercase tracking-widest text-left border-b border-slate-800 bg-[#0f172a]/30">
                                    <th className="px-8 py-5">Rank</th>
                                    <th className="px-8 py-5">Instrument</th>
                                    <th className="px-8 py-5">Heuristic</th>
                                    <th className="px-8 py-5">AI Predict</th>
                                    <th className="px-8 py-5">Confidence</th>
                                    <th className="px-8 py-5"></th>
                                </tr>
                            </thead>
                            <tbody className="divide-y divide-slate-800/50">
                                {filteredResults.map((res, i) => (
                                    <tr 
                                        key={i} 
                                        onClick={() => setSelectedStock(res)}
                                        className="hover:bg-slate-800/80 cursor-pointer transition-all group border-l-2 border-transparent hover:border-blue-500"
                                    >
                                        <td className="px-8 py-5 font-mono text-slate-500 text-lg">{(i + 1).toString().padStart(2, '0')}</td>
                                        <td className="px-8 py-5">
                                            <div className="flex flex-col">
                                                <span className="text-white font-bold text-xl group-hover:text-blue-400 transition-colors tracking-tight">{res.stock.stock.ticker_symbol}</span>
                                                <span className="text-slate-500 text-xs truncate max-w-[250px] font-medium">{res.stock.stock.name}</span>
                                            </div>
                                        </td>
                                        <td className="px-8 py-5 text-slate-300">
                                            <div className="flex items-center gap-3">
                                                <div className="w-16 h-2 bg-slate-900 rounded-full overflow-hidden shadow-inner">
                                                    <div className="h-full bg-blue-500 shadow-[0_0_8px_rgba(59,130,246,0.5)]" style={{ width: `${res.result.heuristicScore * 100}%` }}></div>
                                                </div>
                                                <span className="font-mono font-bold text-blue-100">{res.result.heuristicScore.toFixed(2)}</span>
                                            </div>
                                        </td>
                                        <td className="px-8 py-5 text-lg font-bold text-emerald-400">
                                            +{(res.result.aiPredictedReturn * 100).toFixed(2)}%
                                        </td>
                                        <td className="px-8 py-5">
                                            <span className={`inline-flex items-center gap-2 px-4 py-1.5 rounded-full text-[10px] font-black uppercase tracking-widest ${
                                                res.result.aiPredictedReturn > 0.05 ? 'bg-emerald-500/10 text-emerald-400' : 'bg-blue-500/10 text-blue-400'
                                            }`}>
                                                <div className={`w-1.5 h-1.5 rounded-full animate-pulse ${res.result.aiPredictedReturn > 0.05 ? 'bg-emerald-400' : 'bg-blue-400'}`}></div>
                                                {res.result.aiPredictedReturn > 0.05 ? 'High' : 'Moderate'}
                                            </span>
                                        </td>
                                        <td className="px-8 py-5 text-right">
                                            <ChevronRight className="text-slate-600 group-hover:text-white transition-all translate-x-0 group-hover:translate-x-2" size={24} />
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                </div>

                {/* Sector Heatmap */}
                <div className="bg-[#1e293b] rounded-2xl border border-slate-800 shadow-2xl overflow-hidden mb-12">
                    <div className="px-8 py-6 border-b border-slate-800 flex items-center justify-between bg-slate-900/20">
                        <div className="flex items-center gap-3">
                            <div className="p-2 bg-purple-500/10 rounded-lg text-purple-400 shadow-inner">
                                <BarChart3 size={22} />
                            </div>
                            <h2 className="text-xl font-bold text-white tracking-wide">Sector Performance Heatmap</h2>
                        </div>
                    </div>
                    <div className="h-[500px] p-6">
                        <SectorHeatmap sectors={currentConfig.sectors} exchanges={currentConfig.exchanges} />
                    </div>
                </div>
            </main>

            {/* Details Modal */}
            {selectedStock && (
                <div className="fixed inset-0 bg-[#020617]/90 backdrop-blur-md z-[100] flex items-center justify-end">
                    <div className="w-full max-w-4xl h-full bg-[#1e293b] shadow-2xl border-l border-slate-700 animate-in slide-in-from-right duration-300 overflow-y-auto">
                        <div className="p-8">
                            <div className="flex justify-between items-start mb-8">
                                <div>
                                    <h2 className="text-4xl font-bold text-white mb-2">{selectedStock.stock.stock.ticker_symbol}</h2>
                                    <p className="text-xl text-slate-400">{selectedStock.stock.stock.name}</p>
                                </div>
                                <button 
                                    onClick={() => setSelectedStock(null)}
                                    className="p-2 hover:bg-slate-700 rounded-full text-slate-400 hover:text-white transition-all"
                                >
                                    <X size={32} />
                                </button>
                            </div>

                            <div className="grid grid-cols-3 gap-6 mb-10">
                                <div className="bg-[#0f172a] p-6 rounded-2xl border border-slate-800">
                                    <p className="text-slate-500 text-xs font-bold uppercase mb-2 tracking-widest">30-Day Forecast</p>
                                    <p className="text-3xl font-bold text-emerald-400">
                                        {(selectedStock.result.q50 * 100).toFixed(2)}%
                                    </p>
                                </div>
                                <div className="bg-[#0f172a] p-6 rounded-2xl border border-slate-800">
                                    <p className="text-slate-500 text-xs font-bold uppercase mb-2 tracking-widest">Confidence Interval (90%)</p>
                                    <p className="text-xl font-bold text-slate-300">
                                        {(selectedStock.result.q05 * 100).toFixed(1)}% to {(selectedStock.result.q95 * 100).toFixed(1)}%
                                    </p>
                                </div>
                                <div className="bg-[#0f172a] p-6 rounded-2xl border border-slate-800">
                                    <p className="text-slate-500 text-xs font-bold uppercase mb-2 tracking-widest">Technical Score</p>
                                    <p className="text-3xl font-bold text-blue-400">{selectedStock.result.heuristicScore.toFixed(2)}</p>
                                </div>
                            </div>

                            <div className="h-[450px] mb-8">
                                <StockChart 
                                    data={selectedStock.stock} 
                                    markers={selectedStock.tradePoints} 
                                    comparisonData={comparisonData}
                                />
                            </div>

                            <div className="mb-10">
                                <div className="flex items-center justify-between mb-4">
                                    <h3 className="text-xl font-bold text-white">Performance Comparison</h3>
                                    <div className="relative">
                                        <input 
                                            type="text" 
                                            placeholder="Compare with (e.g. SPY)..." 
                                            value={compSearch}
                                            onChange={(e) => setCompSearch(e.target.value)}
                                            onKeyDown={(e) => e.key === 'Enter' && addComparison(compSearch)}
                                            className="bg-slate-900 border border-slate-700 rounded-lg pl-4 pr-10 py-2 text-sm focus:outline-none focus:border-blue-500 w-64"
                                        />
                                        <button 
                                            onClick={() => addComparison(compSearch)}
                                            className="absolute right-2 top-1/2 -translate-y-1/2 p-1 text-blue-400 hover:text-blue-300"
                                        >
                                            <TrendingUp size={18} />
                                        </button>
                                    </div>
                                </div>
                                <div className="flex flex-wrap gap-3">
                                    <div className="flex items-center gap-2 px-3 py-1.5 rounded-full bg-emerald-500/10 text-emerald-400 border border-emerald-500/20 text-xs font-bold">
                                        {selectedStock.stock.stock.ticker_symbol} (Main)
                                    </div>
                                    {comparisonTickers.map(ticker => (
                                        <div key={ticker} className="flex items-center gap-2 px-3 py-1.5 rounded-full bg-blue-500/10 text-blue-400 border border-blue-500/20 text-xs font-bold">
                                            {ticker}
                                            <button onClick={() => removeComparison(ticker)} className="hover:text-white transition-colors">
                                                <X size={14} />
                                            </button>
                                        </div>
                                    ))}
                                </div>
                            </div>

                            <div className="bg-slate-900/50 p-8 rounded-2xl border border-slate-800">
                                <h3 className="text-xl font-bold text-white mb-4">Signal Rationale</h3>
                                <div className="grid grid-cols-2 gap-8 text-sm">
                                    <div className="space-y-4">
                                        <div className="flex justify-between items-center pb-2 border-b border-slate-800">
                                            <span className="text-slate-400">Analyst Rating</span>
                                            <span className="text-blue-400 font-bold">
                                                {selectedStock.stock?.rating?.length > 0 
                                                    ? (selectedStock.stock.rating[selectedStock.stock.rating.length - 1] || 0).toFixed(2) 
                                                    : 'N/A'} / 5.0
                                            </span>
                                        </div>
                                        <div className="flex justify-between items-center pb-2 border-b border-slate-800">
                                            <span className="text-slate-400">Relative Volume (RVOL)</span>
                                            <span className={`${(selectedStock.result?.rvol || 0) > 1.2 ? 'text-emerald-400' : 'text-slate-200'} font-bold`}>
                                                {(selectedStock.result?.rvol || 0).toFixed(2)}x {(selectedStock.result?.rvol || 0) > 1.2 ? '(High)' : ''}
                                            </span>
                                        </div>
                                    </div>
                                    <div className="space-y-4">
                                        <div className="flex justify-between items-center pb-2 border-b border-slate-800">
                                            <span className="text-slate-400">Volatility Index</span>
                                            <span className={`${(selectedStock.result?.volatility || 0) < 0.02 ? 'text-emerald-400' : 'text-slate-200'} font-bold`}>
                                                {((selectedStock.result?.volatility || 0) * 100).toFixed(2)}% {(selectedStock.result?.volatility || 0) < 0.02 ? '(Compressed)' : ''}
                                            </span>
                                        </div>
                                        <div className="flex justify-between items-center pb-2 border-b border-slate-800">
                                            <span className="text-slate-400">Momentum (MA Trend)</span>
                                            <span className={`${(selectedStock.result?.momentum || 1) > 1.01 ? 'text-emerald-400' : 'text-blue-400'} font-bold`}>
                                                {(((selectedStock.result?.momentum || 1) - 1) * 100).toFixed(2)}% {(selectedStock.result?.momentum || 1) > 1.0 ? 'Upward' : 'Downward'}
                                            </span>
                                        </div>
                                    </div>
                                </div>
                            </div>
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
};

export default StockDashboard;
